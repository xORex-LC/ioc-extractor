package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.application.observation.RegisteredObservation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Lifecycle-aware canonical command for one artifact transaction.
 *
 * @param observationId durable delivery-attempt identity
 * @param sourceKey stable identity of the accepted source content
 * @param receipt bounded prepared-row receipt publication context
 * @param artifactName configured artifact name
 * @param header ordered public artifact columns
 * @param records identity-resolved prepared records
 */
public record CanonicalArtifactConfirmation(ObservationId observationId,
                                            String sourceKey,
                                            ConfirmationReceiptContext receipt,
                                            String artifactName,
                                            List<String> header,
                                            RowSource<CanonicalRecordConfirmation> records,
                                            RegisteredObservation registration) {

    /** Copies metadata; the writer validates streamed rows before reserving identities. */
    public CanonicalArtifactConfirmation {
        Objects.requireNonNull(observationId, "observationId");
        sourceKey = requireText(sourceKey, "sourceKey");
        Objects.requireNonNull(receipt, "receipt");
        artifactName = requireText(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        Objects.requireNonNull(records, "records");
        if (header.isEmpty()) {
            throw new IllegalArgumentException("Artifact header must not be empty");
        }
    }

    public CanonicalArtifactConfirmation(ObservationId observationId, String sourceKey,
            ConfirmationReceiptContext receipt, String artifactName, List<String> header,
            List<CanonicalRecordConfirmation> records, RegisteredObservation registration) {
        this(observationId, sourceKey, receipt, artifactName, header,
                RowSource.of(records), registration);
        var keys = new HashSet<>();
        for (var record : records) {
            if (!keys.add(record.rowKey())) {
                throw new IllegalArgumentException("Canonical confirmation contains duplicate row key: "
                        + record.rowKey().value());
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

    /** Compatibility constructor for artifacts without ordered mutable fields. */
    public CanonicalArtifactConfirmation(ObservationId observationId,
                                         String sourceKey,
                                         ConfirmationReceiptContext receipt,
                                         String artifactName,
                                         List<String> header,
                                         List<CanonicalRecordConfirmation> records) {
        this(observationId, sourceKey, receipt, artifactName, header, records, null);
    }
}
