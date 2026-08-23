package com.iocextractor.application.artifact.lifecycle;

import java.time.Duration;
import java.util.Objects;

/**
 * Receipt publication facts shared by every artifact of one accepted source result.
 *
 * @param id durable receipt identity
 * @param processingPolicyFingerprint identity of the ETL policy that prepared the rows
 * @param expectedArtifacts number of artifact markers required before publication
 * @param retention strictly positive retention applied when the receipt becomes complete
 */
public record ConfirmationReceiptContext(ConfirmationReceiptId id,
                                         String processingPolicyFingerprint,
                                         int expectedArtifacts,
                                         Duration retention) {

    /** Validates the bounded receipt contract. */
    public ConfirmationReceiptContext {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(processingPolicyFingerprint, "processingPolicyFingerprint");
        Objects.requireNonNull(retention, "retention");
        if (processingPolicyFingerprint.isBlank()) {
            throw new IllegalArgumentException("Processing policy fingerprint must not be blank");
        }
        if (expectedArtifacts <= 0) {
            throw new IllegalArgumentException("Expected artifact count must be positive");
        }
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Receipt retention must be positive");
        }
    }
}
