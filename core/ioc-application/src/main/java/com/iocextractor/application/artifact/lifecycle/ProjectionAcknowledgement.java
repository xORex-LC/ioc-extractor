package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Monotonic acknowledgement for one installed mutable artifact snapshot.
 *
 * @param artifactName configured artifact
 * @param installedGeneration generation actually represented by the installed file
 */
public record ProjectionAcknowledgement(String artifactName,
                                        ProjectionGeneration installedGeneration) {

    /** Validates projection identity and prevents acknowledging unseen future work. */
    public ProjectionAcknowledgement {
        artifactName = requireText(artifactName, "artifactName");
        Objects.requireNonNull(installedGeneration, "installedGeneration");
        if (installedGeneration.value() == 0) {
            throw new IllegalArgumentException("Untracked generation cannot be acknowledged");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
