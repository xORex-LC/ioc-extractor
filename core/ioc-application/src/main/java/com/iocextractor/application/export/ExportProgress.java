package com.iocextractor.application.export;

import java.time.Instant;
import java.util.Objects;

/** Last successfully accounted export state for one profile/artifact pair. */
public record ExportProgress(String profile,
                             String artifactName,
                             long lastRevision,
                             String lastSha256,
                             String lastSliceId,
                             String planHash,
                             Instant updatedAt) {

    public ExportProgress {
        profile = ExportArtifactSpec.requireText(profile, "profile");
        artifactName = ExportArtifactSpec.requireText(artifactName, "artifactName");
        if (lastRevision < 0) {
            throw new IllegalArgumentException("Last exported revision must not be negative");
        }
        lastSha256 = ExportArtifactSpec.requireSha256(lastSha256, "lastSha256");
        lastSliceId = ExportArtifactSpec.requireText(lastSliceId, "lastSliceId");
        planHash = ExportArtifactSpec.requireSha256(planHash, "planHash");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
