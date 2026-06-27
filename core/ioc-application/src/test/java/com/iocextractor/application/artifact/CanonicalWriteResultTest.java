package com.iocextractor.application.artifact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalWriteResultTest {

    @Test
    void mutating_write_requires_positive_revision() {
        assertThatThrownBy(() -> new CanonicalWriteResult(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive revision");
    }

    @Test
    void counters_must_not_be_negative() {
        assertThatThrownBy(() -> new CanonicalWriteResult(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalWriteResult(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
