package com.iocextractor.application.artifact.lifecycle;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Lifecycle-aware canonical command for one artifact transaction.
 *
 * @param observationId durable delivery-attempt identity
 * @param artifactName configured artifact name
 * @param header ordered public artifact columns
 * @param records identity-resolved prepared records
 */
public record CanonicalArtifactConfirmation(ObservationId observationId,
                                            String artifactName,
                                            List<String> header,
                                            List<CanonicalRecordConfirmation> records) {

    /** Copies collections and rejects ambiguous duplicate row keys. */
    public CanonicalArtifactConfirmation {
        Objects.requireNonNull(observationId, "observationId");
        artifactName = requireText(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (header.isEmpty()) {
            throw new IllegalArgumentException("Artifact header must not be empty");
        }
        var keys = new HashSet<>();
        for (CanonicalRecordConfirmation record : records) {
            Objects.requireNonNull(record, "records element");
            if (!keys.add(record.rowKey())) {
                throw new IllegalArgumentException(
                        "Canonical confirmation contains duplicate row key: " + record.rowKey().value());
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
