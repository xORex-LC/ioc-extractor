package com.iocextractor.adapter.out.store.jdbc;

import java.util.List;
import java.util.Objects;

/**
 * Desired table-per-artifact dataframe schema.
 */
public record DataframeArtifactSchema(String artifactName, List<DataframeColumn> columns) {

    public DataframeArtifactSchema {
        artifactName = DataframeColumn.requireIdentifier(artifactName, "artifact name");
        Objects.requireNonNull(columns, "columns");
        columns = List.copyOf(columns);
    }
}
