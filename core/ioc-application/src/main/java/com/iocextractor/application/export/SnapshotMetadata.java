package com.iocextractor.application.export;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Metadata captured for all artifacts in one consistent canonical read snapshot.
 */
public record SnapshotMetadata(String profile,
                               String planHash,
                               Instant capturedAt,
                               List<SnapshotArtifactMetadata> artifacts) {

    public SnapshotMetadata {
        profile = ExportArtifactSpec.requireText(profile, "profile");
        planHash = ExportArtifactSpec.requireSha256(planHash, "planHash");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("Snapshot metadata requires at least one artifact");
        }
    }
}
