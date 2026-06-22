package com.iocextractor.application.aggregation;

import com.iocextractor.application.ingest.SourceKey;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Rows read from a source-scoped partition artifact.
 */
public record PartitionArtifact(SourceKey sourceKey, String artifactName, Path path, List<ArtifactRow> rows) {

    public PartitionArtifact {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(artifactName, "artifactName");
        Objects.requireNonNull(path, "path");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }
}
