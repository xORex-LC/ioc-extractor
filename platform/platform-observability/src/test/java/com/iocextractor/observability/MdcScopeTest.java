package com.iocextractor.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MdcScopeTest {

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void puts_values_and_removes_owned_keys_on_close() {
        try (var ignored = MdcScope.open()
                .put(LogField.EVENT_ACTION, EventAction.STAGE_START.value())
                .put(LogField.IOC_RUN_ID, "run-1")) {
            assertThat(MDC.get(LogField.EVENT_ACTION.key())).isEqualTo("stage_start");
            assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isEqualTo("run-1");
        }

        assertThat(MDC.get(LogField.EVENT_ACTION.key())).isNull();
        assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isNull();
    }

    @Test
    void restores_previous_value_and_supports_nested_scopes() {
        MDC.put(LogField.IOC_RUN_ID.key(), "outer");

        try (var first = MdcScope.open().put(LogField.IOC_RUN_ID, "first")) {
            assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isEqualTo("first");
            try (var second = MdcScope.open().put(LogField.IOC_RUN_ID, "second")) {
                assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isEqualTo("second");
            }
            assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isEqualTo("first");
        }

        assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isEqualTo("outer");
    }

    @Test
    void null_value_removes_key_for_the_scope_and_close_is_idempotent() {
        MDC.put(LogField.IOC_SOURCE_ID.key(), "source-1");
        var scope = MdcScope.open().put(LogField.IOC_SOURCE_ID, null);

        assertThat(MDC.get(LogField.IOC_SOURCE_ID.key())).isNull();

        scope.close();
        scope.close();

        assertThat(MDC.get(LogField.IOC_SOURCE_ID.key())).isEqualTo("source-1");
    }

    @Test
    void rejects_writes_after_close() {
        var scope = MdcScope.open();
        scope.close();

        assertThatIllegalStateException()
                .isThrownBy(() -> scope.put(LogField.IOC_RUN_ID, "run-1"));
    }
}
