package com.iocextractor.application.export;

import java.util.List;
import java.util.Objects;

/**
 * Public schema, identity and coverage observed for one artifact in a snapshot.
 */
public record SnapshotArtifactMetadata(String artifactName,
                                       String fileName,
                                       List<String> columns,
                                       ArtifactCoverage coverage,
                                       int identityEpoch,
                                       String identityHash,
                                       String schemaHash) {

    public SnapshotArtifactMetadata {
        artifactName = ExportArtifactSpec.requireText(artifactName, "artifactName");
        fileName = ExportArtifactSpec.requireText(fileName, "fileName");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        coverage = Objects.requireNonNull(coverage, "coverage");
        if (identityEpoch < 1) {
            throw new IllegalArgumentException("Snapshot identity epoch must be positive");
        }
        identityHash = ExportArtifactSpec.requireSha256(identityHash, "identityHash");
        schemaHash = ExportArtifactSpec.requireSha256(schemaHash, "schemaHash");
    }
}
