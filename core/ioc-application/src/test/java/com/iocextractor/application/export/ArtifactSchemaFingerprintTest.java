package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactSchemaFingerprintTest {

    @Test
    void fingerprintIsOrderedAndNormalizesDeclaredTypes() {
        String first = ArtifactSchemaFingerprint.sha256(
                List.of("id", "mask"), java.util.Arrays.asList("integer", null));
        String normalized = ArtifactSchemaFingerprint.sha256(
                List.of("id", "mask"), List.of("INTEGER", "TEXT"));
        String reordered = ArtifactSchemaFingerprint.sha256(
                List.of("mask", "id"), List.of("TEXT", "INTEGER"));

        assertThat(first).isEqualTo(normalized).hasSize(64);
        assertThat(reordered).isNotEqualTo(first);
    }
}
