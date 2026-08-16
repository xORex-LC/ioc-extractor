package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/** Aggregate per-artifact lifecycle counts exposed to operators. */
public record LifecycleArtifactStatistics(String artifactName,
                                          long stored,
                                          long due,
                                          long history) {

    public LifecycleArtifactStatistics {
        artifactName = requireText(artifactName);
        if (stored < 0 || due < 0 || history < 0 || due > stored) {
            throw new IllegalArgumentException("Lifecycle artifact counts are inconsistent");
        }
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "artifactName");
        if (value.isBlank()) {
            throw new IllegalArgumentException("artifactName must not be blank");
        }
        return value;
    }
}
