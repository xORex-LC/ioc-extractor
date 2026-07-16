package com.iocextractor.observability.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LogEventTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("test.typed-log-event");

    @AfterEach
    void resetLoggerAndMdc() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
        logger.setLevel(null);
        MDC.clear();
    }

    @Test
    void emits_typed_event_fields_and_keeps_ambient_correlation_in_mdc() {
        var appender = appender();

        try (var ignored = MdcScope.open().put(LogField.IOC_RUN_ID, "run-1")) {
            LogEvents.info(logger)
                    .action(EventAction.STAGE_COMPLETE)
                    .field(LogField.IOC_ROWS, 7)
                    .field(LogField.IOC_SYNC_SHED_TO_RECONCILE, false)
                    .message("typed event")
                    .log();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getMDCPropertyMap()).containsEntry(LogField.IOC_RUN_ID.key(), "run-1");
            assertThat(eventFields(event))
                    .containsEntry(LogField.EVENT_ACTION.key(), EventAction.STAGE_COMPLETE.value())
                    .containsEntry(LogField.IOC_ROWS.key(), 7L)
                    .containsEntry(LogField.IOC_SYNC_SHED_TO_RECONCILE.key(), false);
        });
    }

    @Test
    void event_local_field_wins_over_same_named_mdc_value_and_mdc_is_restored() {
        var appender = appender();
        MDC.put(LogField.IOC_RUN_ID.key(), "ambient");

        LogEvents.info(logger)
                .field(LogField.IOC_RUN_ID, "event-local")
                .message("collision")
                .log();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getMDCPropertyMap()).doesNotContainKey(LogField.IOC_RUN_ID.key());
            assertThat(eventFields(event)).containsEntry(LogField.IOC_RUN_ID.key(), "event-local");
        });
        assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isEqualTo("ambient");
    }

    @Test
    void public_facade_rejects_type_mismatches_and_omits_null_fields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogEvents.info(logger).field(LogField.IOC_ROWS, "7"))
                .withMessageContaining(LogField.IOC_ROWS.key())
                .withMessageContaining("LONG");

        var appender = appender();
        LogEvents.info(logger)
                .field(LogField.IOC_SOURCE_ID, null)
                .message("null field")
                .log();

        assertThat(appender.list).singleElement()
                .satisfies(event -> assertThat(eventFields(event))
                        .doesNotContainKey(LogField.IOC_SOURCE_ID.key()));
    }

    @Test
    void async_handoff_preserves_typed_fields_and_producer_mdc_snapshot() throws Exception {
        var downstream = new AwaitingListAppender();
        downstream.start();
        var async = new AsyncAppender();
        async.setContext(logger.getLoggerContext());
        async.addAppender(downstream);
        async.start();
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        logger.addAppender(async);

        try (var ignored = MdcScope.open().put(LogField.IOC_RUN_ID, "run-before-handoff")) {
            LogEvents.info(logger)
                    .field(LogField.IOC_ROWS, 11)
                    .message("async event")
                    .log();
        }
        MDC.put(LogField.IOC_RUN_ID.key(), "run-after-handoff");

        assertThat(downstream.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(downstream.list).singleElement().satisfies(event -> {
            assertThat(event.getMDCPropertyMap())
                    .containsEntry(LogField.IOC_RUN_ID.key(), "run-before-handoff");
            assertThat(eventFields(event)).containsEntry(LogField.IOC_ROWS.key(), 11L);
        });
        async.stop();
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        var appender = new PreparingListAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static Map<String, Object> eventFields(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        var pairs = event.getKeyValuePairs();
        if (pairs != null) {
            pairs.forEach(pair -> fields.put(pair.key, pair.value));
        }
        return fields;
    }

    private static final class AwaitingListAppender extends ListAppender<ILoggingEvent> {
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        protected void append(ILoggingEvent eventObject) {
            super.append(eventObject);
            completed.countDown();
        }
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
