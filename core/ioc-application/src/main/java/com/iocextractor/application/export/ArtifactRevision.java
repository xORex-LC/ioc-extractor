package com.iocextractor.application.export;

import java.time.Instant;

/** Current canonical change marker for one artifact. */
public record ArtifactRevision(String artifactName, long revision, Instant changedAt) {

    public ArtifactRevision {
        artifactName = ExportArtifactSpec.requireText(artifactName, "artifactName");
        if (revision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        if (revision == 0 && changedAt != null) {
            throw new IllegalArgumentException("An unchanged artifact must not have changedAt");
        }
        if (revision > 0 && changedAt == null) {
            throw new IllegalArgumentException("A changed artifact requires changedAt");
        }
    }
}
