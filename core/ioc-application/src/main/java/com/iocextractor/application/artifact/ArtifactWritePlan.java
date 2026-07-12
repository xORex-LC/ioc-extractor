package com.iocextractor.application.artifact;

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
        int idCount = (int) rows.stream().filter(row -> row.idColumn().isPresent()).count();
        ArtifactIdReservation ids = idSequence.reserve(idCount);
        int idOffset = 0;
        var materialized = new ArrayList<ArtifactRow>(rows.size());
        for (PreparedArtifactRow row : rows) {
            Long id = row.idColumn().isPresent() ? ids.idAt(idOffset++) : null;
            materialized.add(row.materialize(id));
        }
        return new CanonicalArtifact(artifactName, header, materialized);
    }
}
