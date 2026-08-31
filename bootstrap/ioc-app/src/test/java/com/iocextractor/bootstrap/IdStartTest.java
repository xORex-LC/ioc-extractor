package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdStartTest {

    @Test
    void invalid_text_preserves_the_numeric_parse_failure() {
        assertThatThrownBy(() -> IdStart.parse("not-a-number"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void out_of_range_number_preserves_the_numeric_parse_failure() {
        assertThatThrownBy(() -> IdStart.from(new java.math.BigInteger("9223372036854775808")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(NumberFormatException.class);
    }
}
