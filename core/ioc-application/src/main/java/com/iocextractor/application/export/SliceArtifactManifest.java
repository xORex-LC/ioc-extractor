package com.iocextractor.application.export;

import java.util.Objects;

/**
 * Integrity and provenance entry for one data file in a completed slice manifest.
 */
public record SliceArtifactManifest(String artifactName,
                                    String fileName,
                                    long rows,
                                    ArtifactCoverage coverage,
                                    int identityEpoch,
                                    String identityHash,
                                    String schemaHash,
                                    String sha256) {

    public SliceArtifactManifest {
        artifactName = ExportArtifactSpec.requireText(artifactName, "artifactName");
        fileName = ExportArtifactSpec.requireText(fileName, "fileName");
        if (rows < 0) {
            throw new IllegalArgumentException("Manifest row count must not be negative");
        }
        coverage = Objects.requireNonNull(coverage, "coverage");
        if (identityEpoch < 1) {
            throw new IllegalArgumentException("Manifest identity epoch must be positive");
        }
        identityHash = ExportArtifactSpec.requireSha256(identityHash, "identityHash");
        schemaHash = ExportArtifactSpec.requireSha256(schemaHash, "schemaHash");
        sha256 = ExportArtifactSpec.requireSha256(sha256, "sha256");
    }
}
