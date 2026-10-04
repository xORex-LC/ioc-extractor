package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.application.observation.RegisteredObservation;

import java.util.List;
import java.util.Objects;

/** Immutable, side-effect-free artifact plan produced before the policy checkpoint. */
public record ArtifactWritePlan(String artifactName,
                                List<String> header,
                                RowSource<PreparedArtifactRow> rows,
                                ArtifactIdSequence idSequence) {

    public ArtifactWritePlan {
        Objects.requireNonNull(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(idSequence, "idSequence");
    }

    public ArtifactWritePlan(String artifactName, List<String> header,
                             List<PreparedArtifactRow> rows, ArtifactIdSequence idSequence) {
        this(artifactName, header, RowSource.of(rows), idSequence);
    }

    /** Reserves final ids and materializes a canonical artifact for one commit attempt. */
    public CanonicalArtifact materialize() {
        return new CanonicalArtifact(artifactName, header,
                reservedRows().map(CanonicalWriteRow::row).snapshot());
    }

    /** Reserves ids while retaining ordered-field positions for canonical mutation. */
    public CanonicalWriteCommand materializeCommand(RegisteredObservation registration) {
        return new CanonicalWriteCommand(artifactName, header, reservedRows(), registration);
    }

    private RowSource<CanonicalWriteRow> reservedRows() {
        int[] count = {0};
        rows.forEach(row -> { if (row.idColumn().isPresent()) { count[0]++; } });
        ArtifactIdReservation ids = idSequence.reserve(count[0]);
        return new RowSource<CanonicalWriteRow>() {
            public int size() { return rows.size(); }
            public RowCursor<CanonicalWriteRow> open() {
                var cursor = rows.open();
                return new RowCursor<>() {
                    private int offset;
                    private CanonicalWriteRow value;
                    public boolean next() {
                        if (!cursor.next()) { value = null; return false; }
                        var row = cursor.value();
                        Long id = row.idColumn().isPresent() ? ids.idAt(offset++) : null;
                        value = new CanonicalWriteRow(row.materialize(id), row.orderedFieldPositions());
                        return true;
                    }
                    public CanonicalWriteRow value() { return Objects.requireNonNull(value, "current row"); }
                    public void close() { value = null; cursor.close(); }
                };
            }
        };
    }
}
