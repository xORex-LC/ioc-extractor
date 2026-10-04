package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.application.observation.RegisteredObservation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Compatibility-mode canonical write with optional durable field precedence. */
public record CanonicalWriteCommand(String artifactName,
                                    List<String> header,
                                    RowSource<CanonicalWriteRow> rows,
                                    RegisteredObservation registration) {

    public CanonicalWriteCommand {
        Objects.requireNonNull(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        Objects.requireNonNull(rows, "rows");
        boolean ordered = rows.anyMatch(row -> !row.orderedFieldPositions().isEmpty());
        if (ordered && registration == null) {
            throw new IllegalArgumentException("Ordered fields require a registered observation");
        }
    }

    public CanonicalWriteCommand(String artifactName, List<String> header,
                                 List<CanonicalWriteRow> rows, RegisteredObservation registration) {
        this(artifactName, header, RowSource.of(rows), registration);
    }

    public Optional<RegisteredObservation> registrationOptional() {
        return Optional.ofNullable(registration);
    }

    public CanonicalArtifact artifact() {
        return new CanonicalArtifact(artifactName, header, rows.map(CanonicalWriteRow::row).snapshot());
    }
}
