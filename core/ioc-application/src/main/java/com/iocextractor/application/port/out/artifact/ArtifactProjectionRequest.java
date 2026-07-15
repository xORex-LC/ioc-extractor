package com.iocextractor.application.port.out.artifact;

import java.util.Objects;

/**
 * Identifies one derived-artifact projection operation.
 *
 * @param runId correlation identifier supplied by the calling operation
 * @param artifactName configured artifact to project
 */
public record ArtifactProjectionRequest(String runId, String artifactName) {

    /** Validates required projection identity. */
    public ArtifactProjectionRequest {
        runId = requireText(runId, "runId");
        artifactName = requireText(artifactName, "artifactName");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
