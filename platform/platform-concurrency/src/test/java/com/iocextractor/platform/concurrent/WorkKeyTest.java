package com.iocextractor.platform.concurrent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkKeyTest {

    @Test
    void createsKeyFromNonBlankText() {
        assertThat(WorkKey.of("endpoint-a").value()).isEqualTo("endpoint-a");
    }

    @Test
    void rejectsBlankText() {
        assertThatThrownBy(() -> WorkKey.of(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }
}
