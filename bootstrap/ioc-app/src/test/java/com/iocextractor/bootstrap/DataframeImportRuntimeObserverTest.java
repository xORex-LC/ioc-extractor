package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataframeImportRuntimeObserverTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);
    private static final String PRIVATE_DETAIL = "smb://user:password@server/private.csv";

    private final Logger logger = (Logger) LoggerFactory.getLogger(DataframeImportRuntimeObserver.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

    @Test
    void emitsStartupRecoveryAndNonEmptyRetentionAggregates() {
        var appender = appender();
        var observer = new DataframeImportRuntimeObserver(ignored -> { }, CLOCK);

        observer.recoveryStarted();
        observer.recoveryCompleted(new RecoverDataframeImportsResult(3, 2, 0), Duration.ofMillis(4));
        observer.retentionCompleted(0, Duration.ofMillis(1));
        observer.retentionCompleted(7, Duration.ofMillis(2));

        assertThat(appender.list).extracting(event -> fields(event).get(LogField.EVENT_ACTION.key()))
                .containsExactly(
                        EventAction.IMPORT_RECOVER.value(),
                        EventAction.IMPORT_RECOVER.value(),
                        EventAction.IMPORT_RETENTION.value());
        assertThat(fields(appender.list.get(1)))
                .containsEntry(LogField.IOC_IMPORT_RECOVERY_EXAMINED.key(), 3L)
                .containsEntry(LogField.IOC_IMPORT_RECOVERY_ADVANCED.key(), 2L);
        assertThat(fields(appender.list.getLast()))
                .containsEntry(LogField.IOC_IMPORT_RETAINED.key(), 7L);
    }

    @Test
    void emitsValueFreeFailureAndMatchingDiagnostic() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        var appender = appender();
        var observer = new DataframeImportRuntimeObserver(diagnostics::add, CLOCK);

        observer.changeSignalFailed(IllegalStateException.class.getName());

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(fields(event))
                    .containsEntry(LogField.EVENT_ACTION.key(), EventAction.IMPORT_CHANGE_SIGNAL.value())
                    .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.FAILURE.value())
                    .containsEntry(LogField.ERROR_TYPE.key(), IllegalStateException.class.getName());
            assertThat(event.toString()).doesNotContain(PRIVATE_DETAIL);
        });
        assertThat(diagnostics).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.code().id()).isEqualTo("IMPORT.CHANGE_SIGNAL_FAILED"));
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

    private static Map<String, Object> fields(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
