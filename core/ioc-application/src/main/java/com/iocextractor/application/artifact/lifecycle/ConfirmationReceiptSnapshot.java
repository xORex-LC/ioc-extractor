package com.iocextractor.application.artifact.lifecycle;

import java.util.List;
import java.util.Objects;

/** Complete, unexpired prepared-row receipt eligible for no-ETL confirmation. */
public record ConfirmationReceiptSnapshot(ConfirmationReceiptId id,
                                          String sourceKey,
                                          String processingPolicyFingerprint,
                                          List<ConfirmationReceiptArtifact> artifacts) {

    public ConfirmationReceiptSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(processingPolicyFingerprint, "processingPolicyFingerprint");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (sourceKey.isBlank() || processingPolicyFingerprint.isBlank() || artifacts.isEmpty()) {
            throw new IllegalArgumentException("Complete receipt facts must not be blank or empty");
        }
    }
}
