package com.iocextractor.application.artifact;

import java.util.Objects;

/**
 * Stable artifact row identity within one artifact.
 */
public record ArtifactRowKey(String value) {

    public ArtifactRowKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Artifact row key must not be blank");
        }
    }
}
