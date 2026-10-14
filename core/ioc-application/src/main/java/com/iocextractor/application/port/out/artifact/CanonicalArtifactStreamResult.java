package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;

import java.util.Objects;

/** Row count and projection coverage read in the same transaction as the streamed rows. */
public record CanonicalArtifactStreamResult(int rows, ProjectionGeneration generation) {
    /** Validates snapshot evidence; generation zero denotes an untracked disabled lifecycle. */
    public CanonicalArtifactStreamResult {
        if (rows < 0) {
            throw new IllegalArgumentException("Streamed rows must not be negative");
        }
        Objects.requireNonNull(generation, "generation");
    }
}
