package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Durable mutable-projection convergence state for one artifact.
 *
 * @param artifactName configured artifact
 * @param requiredGeneration latest canonical generation that must be projected
 * @param projectedGeneration latest successfully installed and acknowledged generation
 */
public record ArtifactProjectionState(String artifactName,
                                      ProjectionGeneration requiredGeneration,
                                      ProjectionGeneration projectedGeneration) {

    /** Ensures acknowledgement never leads canonical truth. */
    public ArtifactProjectionState {
        artifactName = requireText(artifactName, "artifactName");
        Objects.requireNonNull(requiredGeneration, "requiredGeneration");
        Objects.requireNonNull(projectedGeneration, "projectedGeneration");
        if (projectedGeneration.compareTo(requiredGeneration) > 0) {
            throw new IllegalArgumentException("Projected generation cannot exceed required generation");
        }
    }

    /** Returns whether mutable projection work remains. */
    public boolean pending() {
        return projectedGeneration.compareTo(requiredGeneration) < 0;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
