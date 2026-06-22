package com.iocextractor.application.aggregation;

import java.util.List;
import java.util.Objects;

/**
 * Current canonical artifact snapshot.
 *
 * @param name artifact name
 * @param header stable column order
 * @param rows artifact rows
 */
public record CanonicalArtifact(String name, List<String> header, List<ArtifactRow> rows) {

    public CanonicalArtifact {
        Objects.requireNonNull(name, "name");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }
}
