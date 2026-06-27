package com.iocextractor.application.export;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Transport-neutral integrity root for one immutable export slice.
 */
public record SliceManifest(int manifestVersion,
                            String sliceId,
                            String runId,
                            String profile,
                            Instant createdAt,
                            ExportMode outputMode,
                            String planHash,
                            ExportFormat format,
                            List<SliceArtifactManifest> artifacts) {

    public SliceManifest {
        if (manifestVersion < 1) {
            throw new IllegalArgumentException("Manifest version must be positive");
        }
        sliceId = ExportArtifactSpec.requireText(sliceId, "sliceId");
        runId = ExportArtifactSpec.requireText(runId, "runId");
        if (!sliceId.equals(runId)) {
            throw new IllegalArgumentException("Slice id must equal run id in v1");
        }
        profile = ExportArtifactSpec.requireText(profile, "profile");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        outputMode = Objects.requireNonNull(outputMode, "outputMode");
        planHash = ExportArtifactSpec.requireSha256(planHash, "planHash");
        format = Objects.requireNonNull(format, "format");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("Slice manifest requires at least one artifact");
        }
        List<String> names = artifacts.stream().map(SliceArtifactManifest::artifactName).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("Slice manifest artifacts must be unique");
        }
    }
}
