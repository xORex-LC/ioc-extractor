package com.iocextractor.application.artifact.lifecycle;

import java.util.List;
import java.util.Objects;

/**
 * Active-only canonical snapshot for one artifact and one explicit effective time.
 *
 * @param artifactName configured artifact
 * @param header ordered public columns
 * @param records records active at {@code asOf}
 * @param artifactRevision insert-driven revision observed by the read snapshot
 * @param projectionGeneration mutable-projection generation represented by the snapshot
 * @param asOf effective read boundary
 */
public record ActiveArtifactSnapshot(String artifactName,
                                     List<String> header,
                                     List<ActiveArtifactRecord> records,
                                     long artifactRevision,
                                     ProjectionGeneration projectionGeneration,
                                     EffectiveTime asOf) {

    /** Copies collections and prevents an adapter from returning a due row as active. */
    public ActiveArtifactSnapshot {
        artifactName = requireText(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        Objects.requireNonNull(projectionGeneration, "projectionGeneration");
        Objects.requireNonNull(asOf, "asOf");
        if (artifactRevision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        for (ActiveArtifactRecord record : records) {
            Objects.requireNonNull(record, "records element");
            if (!record.lifecycle().isActiveAt(asOf)) {
                throw new IllegalArgumentException("Active snapshot contains a due lifecycle");
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
