package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Outcome of one bounded archive/delete transaction for an artifact.
 *
 * @param artifactName affected artifact
 * @param cycleAsOf fixed effective time shared by the reconciliation cycle
 * @param expired number of lifecycles archived and removed
 * @param moreDue whether another bounded batch is currently due for the same cycle
 * @param artifactRevision unchanged insert-driven revision observed after the transaction
 * @param requiredProjectionGeneration mutable-projection generation observed after the transaction
 */
public record ExpiryBatchResult(String artifactName,
                                EffectiveTime cycleAsOf,
                                int expired,
                                boolean moreDue,
                                long artifactRevision,
                                ProjectionGeneration requiredProjectionGeneration) {

    /** Validates batch counters and generation state. */
    public ExpiryBatchResult {
        artifactName = requireText(artifactName, "artifactName");
        Objects.requireNonNull(cycleAsOf, "cycleAsOf");
        Objects.requireNonNull(requiredProjectionGeneration, "requiredProjectionGeneration");
        if (expired < 0) {
            throw new IllegalArgumentException("Expired row count must not be negative");
        }
        if (artifactRevision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        if (expired > 0 && requiredProjectionGeneration.value() == 0) {
            throw new IllegalArgumentException("Expired rows require mutable projection work");
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
