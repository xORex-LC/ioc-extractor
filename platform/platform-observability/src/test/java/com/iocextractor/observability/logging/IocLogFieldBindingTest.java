package com.iocextractor.observability.logging;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.LogField;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@ResourceLock("slf4j-mdc-adapter")
class IocLogFieldBindingTest {

    @Test
    void normalizes_supported_text_values_to_strings() {
        var id = UUID.randomUUID();

        assertThat(emittedValue(LogField.IOC_SOURCE_ID, new StringBuilder("source")))
                .isEqualTo("source");
        assertThat(emittedValue(LogField.IOC_SOURCE_PATH, Path.of("source.htm")))
                .isEqualTo("source.htm");
        assertThat(emittedValue(LogField.EVENT_ACTION, EventAction.APP_START))
                .isEqualTo("APP_START");
        assertThat(emittedValue(LogField.IOC_RUN_ID, id))
                .isEqualTo(id.toString());
    }

    @Test
    void normalizes_integral_values_to_long() {
        assertThat(emittedValue(LogField.IOC_ROWS, (byte) 1)).isEqualTo(1L);
        assertThat(emittedValue(LogField.IOC_ROWS, (short) 2)).isEqualTo(2L);
        assertThat(emittedValue(LogField.IOC_ROWS, 3)).isEqualTo(3L);
        assertThat(emittedValue(LogField.IOC_ROWS, 4L)).isEqualTo(4L);
    }

    @Test
    void preserves_boolean_and_omits_null() {
        assertThat(emittedValue(LogField.IOC_SYNC_SHED_TO_RECONCILE, true)).isEqualTo(true);
        assertThat(emittedValue(LogField.IOC_ROWS, null)).isNull();
    }

    @Test
    void rejects_values_that_do_not_match_the_schema() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> emittedValue(LogField.IOC_ROWS, 1.5))
                .withMessageContaining(LogField.IOC_ROWS.key())
                .withMessageContaining("LONG");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> emittedValue(LogField.IOC_RUN_ID, 17))
                .withMessageContaining("STRING");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> emittedValue(LogField.IOC_SYNC_SHED_TO_RECONCILE, "true"))
                .withMessageContaining("BOOLEAN");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> emittedValue(LogField.IOC_RUN_ID, Map.of("id", "run")))
                .withMessageContaining("java.util.");
    }
    private Object emittedValue(LogField field, Object value) {
        var logger = (Logger) LoggerFactory.getLogger("test.ioc-field-binding");
        var previousLevel = logger.getLevel();
        boolean additive = logger.isAdditive();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        try {
            LogEvents.info(logger).field(field, value).log();
            assertThat(appender.list).hasSize(1);
            var fields = appender.list.getFirst().getKeyValuePairs();
            return fields == null || fields.isEmpty() ? null : fields.getFirst().value;
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(additive);
        }
    }
}
