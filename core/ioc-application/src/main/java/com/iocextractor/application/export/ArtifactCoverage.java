package com.iocextractor.application.export;

import java.time.Instant;

/**
 * Per-artifact marker captured inside the same read snapshot as exported rows.
 */
public record ArtifactCoverage(long revision, Instant changedAt, long upperId) {

    public ArtifactCoverage {
        if (revision < 0 || upperId < 0) {
            throw new IllegalArgumentException("Artifact coverage values must not be negative");
        }
        if (revision == 0 && changedAt != null) {
            throw new IllegalArgumentException("An unchanged artifact must not have changedAt");
        }
        if (revision > 0 && changedAt == null) {
            throw new IllegalArgumentException("A changed artifact requires changedAt");
        }
    }

    /** Returns the empty-table coverage used before the first canonical write. */
    public static ArtifactCoverage empty() {
        return new ArtifactCoverage(0, null, 0);
    }
}
