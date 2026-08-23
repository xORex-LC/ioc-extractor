package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Compare-and-set acknowledgement for one installed mutable artifact snapshot.
 *
 * @param artifactName configured artifact
 * @param expectedRequiredGeneration required generation observed before projection
 * @param installedGeneration generation actually represented by the installed file
 */
public record ProjectionAcknowledgement(String artifactName,
                                        ProjectionGeneration expectedRequiredGeneration,
                                        ProjectionGeneration installedGeneration) {

    /** Validates projection identity and prevents acknowledging unseen future work. */
    public ProjectionAcknowledgement {
        artifactName = requireText(artifactName, "artifactName");
        Objects.requireNonNull(expectedRequiredGeneration, "expectedRequiredGeneration");
        Objects.requireNonNull(installedGeneration, "installedGeneration");
        if (!installedGeneration.equals(expectedRequiredGeneration)) {
            throw new IllegalArgumentException(
                    "Installed generation must equal the observed required generation");
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
