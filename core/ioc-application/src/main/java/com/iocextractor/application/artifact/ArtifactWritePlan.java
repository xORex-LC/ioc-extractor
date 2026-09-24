package com.iocextractor.application.artifact;

import com.iocextractor.application.observation.RegisteredObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable, side-effect-free artifact plan produced before the policy checkpoint. */
public record ArtifactWritePlan(String artifactName,
                                List<String> header,
                                List<PreparedArtifactRow> rows,
                                ArtifactIdSequence idSequence) {

    public ArtifactWritePlan {
        Objects.requireNonNull(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        Objects.requireNonNull(idSequence, "idSequence");
    }

    /** Reserves final ids and materializes a canonical artifact for one commit attempt. */
    public CanonicalArtifact materialize() {
        return new CanonicalArtifact(artifactName, header,
                materializeRows().stream().map(CanonicalWriteRow::row).toList());
    }

    /** Reserves ids while retaining ordered-field positions for canonical mutation. */
    public CanonicalWriteCommand materializeCommand(RegisteredObservation registration) {
        return new CanonicalWriteCommand(artifactName, header, materializeRows(), registration);
    }

    private List<CanonicalWriteRow> materializeRows() {
        int idCount = (int) rows.stream().filter(row -> row.idColumn().isPresent()).count();
        ArtifactIdReservation ids = idSequence.reserve(idCount);
        int idOffset = 0;
        var materialized = new ArrayList<CanonicalWriteRow>(rows.size());
        for (PreparedArtifactRow row : rows) {
            Long id = row.idColumn().isPresent() ? ids.idAt(idOffset++) : null;
            materialized.add(new CanonicalWriteRow(
                    row.materialize(id), row.orderedFieldPositions()));
        }
        return List.copyOf(materialized);
    }
}
