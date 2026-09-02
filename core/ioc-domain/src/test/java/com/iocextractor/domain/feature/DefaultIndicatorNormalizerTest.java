package com.iocextractor.domain.feature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultIndicatorNormalizerTest {

    private final IndicatorNormalizer normalizer = new DefaultIndicatorNormalizer();

    @ParameterizedTest
    @NullAndEmptySource
    void preserves_absent_input(String value) {
        assertThat(normalizer.normalize(value)).isEqualTo(value);
    }

    @Test
    void strips_input_containing_only_boundary_punctuation() {
        assertThat(normalizer.normalize(" ;,\"'\t\r\n")).isEmpty();
    }

    @Test
    void strips_trailing_semicolon() {
        assertThat(normalizer.normalize("voffice.top;")).isEqualTo("voffice.top");
    }

    @Test
    void strips_surrounding_quotes_and_whitespace() {
        assertThat(normalizer.normalize("  \"x.com\" ")).isEqualTo("x.com");
    }

    @Test
    void strips_trailing_comma() {
        assertThat(normalizer.normalize("a.b,")).isEqualTo("a.b");
    }

    @Test
    void leaves_clean_value_untouched() {
        assertThat(normalizer.normalize("a.b/c")).isEqualTo("a.b/c");
    }

    @Test
    void keeps_trailing_dot() {
        assertThat(normalizer.normalize("a.b.")).isEqualTo("a.b.");
    }
}
