package com.iocextractor.observability.logging;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LogValueNormalizerTest {

    @Test
    void normalizes_supported_text_values_to_strings() {
        var id = UUID.randomUUID();

        assertThat(LogValueNormalizer.normalize(LogField.IOC_SOURCE_ID, new StringBuilder("source")))
                .isEqualTo("source");
        assertThat(LogValueNormalizer.normalize(LogField.IOC_SOURCE_PATH, Path.of("source.htm")))
                .isEqualTo("source.htm");
        assertThat(LogValueNormalizer.normalize(LogField.EVENT_ACTION, EventAction.APP_START))
                .isEqualTo("APP_START");
        assertThat(LogValueNormalizer.normalize(LogField.IOC_RUN_ID, id))
                .isEqualTo(id.toString());
    }

    @Test
    void normalizes_integral_values_to_long() {
        assertThat(LogValueNormalizer.normalize(LogField.IOC_ROWS, (byte) 1)).isEqualTo(1L);
        assertThat(LogValueNormalizer.normalize(LogField.IOC_ROWS, (short) 2)).isEqualTo(2L);
        assertThat(LogValueNormalizer.normalize(LogField.IOC_ROWS, 3)).isEqualTo(3L);
        assertThat(LogValueNormalizer.normalize(LogField.IOC_ROWS, 4L)).isEqualTo(4L);
    }

    @Test
    void preserves_boolean_and_omits_null() {
        assertThat(LogValueNormalizer.normalize(LogField.IOC_SYNC_SHED_TO_RECONCILE, true)).isEqualTo(true);
        assertThat(LogValueNormalizer.normalize(LogField.IOC_ROWS, null)).isNull();
    }

    @Test
    void rejects_values_that_do_not_match_the_schema() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogValueNormalizer.normalize(LogField.IOC_ROWS, 1.5))
                .withMessageContaining(LogField.IOC_ROWS.key())
                .withMessageContaining("LONG");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogValueNormalizer.normalize(LogField.IOC_RUN_ID, 17))
                .withMessageContaining("STRING");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogValueNormalizer.normalize(LogField.IOC_SYNC_SHED_TO_RECONCILE, "true"))
                .withMessageContaining("BOOLEAN");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogValueNormalizer.normalize(LogField.IOC_RUN_ID, Map.of("id", "run")))
                .withMessageContaining("java.util.");
    }
}
