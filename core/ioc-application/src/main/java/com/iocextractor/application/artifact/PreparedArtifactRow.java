package com.iocextractor.application.artifact;

import java.util.Objects;
import java.util.Optional;

/** Side-effect-free mapped row with an optional deferred public-id slot. */
public record PreparedArtifactRow(ArtifactRow template, Optional<String> idColumn) {

    public PreparedArtifactRow {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(idColumn, "idColumn");
    }

    /** Materializes the deferred id when this row declares an id slot. */
    public ArtifactRow materialize(Long id) {
        return idColumn.map(column -> template.withValue(column, Long.toString(
                Objects.requireNonNull(id, "id")))).orElse(template);
    }
}
