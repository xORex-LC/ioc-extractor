package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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

    @Test
    void fingerprintRejectsIncompleteOrAmbiguousSchemas() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ArtifactSchemaFingerprint.sha256(List.of(), List.of()))
                .withMessage("Schema columns and declared types must be non-empty and aligned");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ArtifactSchemaFingerprint.sha256(
                        List.of("id", "mask"), List.of("INTEGER")))
                .withMessage("Schema columns and declared types must be non-empty and aligned");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ArtifactSchemaFingerprint.sha256(
                        List.of("id", " "), List.of("INTEGER", "TEXT")))
                .withMessage("Schema column must not be blank");
        assertThatNullPointerException()
                .isThrownBy(() -> ArtifactSchemaFingerprint.sha256(
                        java.util.Arrays.asList("id", null), List.of("INTEGER", "TEXT")));
    }

    @Test
    void fingerprintTrimsAndCaseNormalizesNonBlankTypes() {
        assertThat(ArtifactSchemaFingerprint.sha256(List.of("id"), List.of(" integer ")))
                .isEqualTo(ArtifactSchemaFingerprint.sha256(List.of("id"), List.of("INTEGER")));
    }
}
