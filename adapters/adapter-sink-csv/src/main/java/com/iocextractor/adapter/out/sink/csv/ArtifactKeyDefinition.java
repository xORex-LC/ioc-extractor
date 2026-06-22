package com.iocextractor.adapter.out.sink.csv;

import java.util.List;
import java.util.Objects;

/**
 * Adapter-level artifact identity definition derived from configuration.
 *
 * @param artifactName artifact name
 * @param columns key candidate columns
 * @param firstNonEmpty whether the first non-empty column identifies the row
 */
public record ArtifactKeyDefinition(String artifactName, List<String> columns, boolean firstNonEmpty) {

    public ArtifactKeyDefinition {
        Objects.requireNonNull(artifactName, "artifactName");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Artifact key columns must not be empty");
        }
    }
}
