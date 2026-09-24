package com.iocextractor.application.artifact;

import com.iocextractor.application.observation.OccurrencePosition;

import java.util.Map;
import java.util.Objects;

/** One materialized canonical row plus ordered mutable-field observations. */
public record CanonicalWriteRow(ArtifactRow row,
                                Map<String, OccurrencePosition> orderedFieldPositions) {

    public CanonicalWriteRow {
        Objects.requireNonNull(row, "row");
        orderedFieldPositions = Map.copyOf(Objects.requireNonNull(
                orderedFieldPositions, "orderedFieldPositions"));
    }
}
