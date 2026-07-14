package com.iocextractor.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveLogValueSanitizerTest {

    @Test
    void redactsQueryAndPreservesFragment() {
        assertThat(SensitiveLogValueSanitizer.sanitize(
                "https://example.test/path?token=secret#section"))
                .isEqualTo("https://example.test/path?<redacted>#section");
    }

    @Test
    void redactsUrlUserInfo() {
        assertThat(SensitiveLogValueSanitizer.sanitize(
                "https://operator:password@example.test/path"))
                .isEqualTo("https://<redacted>@example.test/path");
    }

    @Test
    void acceptsPartialAndPlainValuesWithoutNormalization() {
        assertThat(SensitiveLogValueSanitizer.sanitize("example.test/path"))
                .isEqualTo("example.test/path");
        assertThat(SensitiveLogValueSanitizer.sanitize("example.test/path?secret"))
                .isEqualTo("example.test/path?<redacted>");
        assertThat(SensitiveLogValueSanitizer.sanitize(null)).isNull();
    }
}
