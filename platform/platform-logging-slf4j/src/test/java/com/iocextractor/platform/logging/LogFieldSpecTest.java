package com.iocextractor.platform.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class LogFieldSpecTest {
    @ParameterizedTest
    @ValueSource(strings = {"collector.task.id", "count", "_private.value_2", "Collector.task-id", "a0.B_2-c"})
    void acceptsDottedPathsWithoutChangingTheirSpelling(String key) {
        assertThat(new LogFieldSpec(key, LogValueType.STRING).key()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " collector.id", "collector.id ", ".id", "collector.",
            "collector..id", "1collector", "collector.1id", "collector/id", "collector id",
            "collector\nid", "@timestamp", "évent.action", "collector.$id"})
    void rejectsInvalidPathsRatherThanTrimmingOrNormalizing(String key) {
        assertThatIllegalArgumentException().isThrownBy(() -> new LogFieldSpec(key, LogValueType.STRING));
    }

    @Test
    void rejectsMissingComponents() {
        assertThatNullPointerException().isThrownBy(() -> new LogFieldSpec(null, LogValueType.STRING));
        assertThatNullPointerException().isThrownBy(() -> new LogFieldSpec("collector.items", null));
    }

    @Test
    void deeplySegmentedPathsValidateWithoutExhaustingTheCallStack() {
        var key = "collector.".repeat(10_000) + "id";
        assertThat(new LogFieldSpec(key, LogValueType.STRING).key()).isEqualTo(key);
        assertThatIllegalArgumentException().isThrownBy(() -> new LogFieldSpec(key + ".", LogValueType.STRING));
    }

    @Test
    void descriptorValueEqualityIncludesTheDeclaredType() {
        var count = new LogFieldSpec("collector.items", LogValueType.LONG);
        assertThat(count).isEqualTo(new LogFieldSpec("collector.items", LogValueType.LONG))
                .isNotEqualTo(new LogFieldSpec("collector.items", LogValueType.STRING));
    }
}
