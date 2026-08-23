package com.iocextractor.application.dataframeimport.model;

import java.time.Instant;
import java.util.Objects;

/** CAS retry or capacity-deferral schedule for one nonterminal delivery. */
public record ImportRetrySchedule(
        ImportDeliveryId deliveryId,
        ImportDeliveryState expectedState,
        long expectedVersion,
        Instant nextAttemptAt,
        String safeCode,
        boolean failedAttempt,
        Instant occurredAt) {

    /** Enforces bounded safe retry metadata. */
    public ImportRetrySchedule {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(safeCode, "safeCode");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (expectedVersion < 0 || safeCode.isBlank()) {
            throw new IllegalArgumentException("Retry schedule requires a non-negative version and safe code");
        }
        if (expectedState == ImportDeliveryState.TERMINAL) {
            throw new IllegalArgumentException("Terminal import delivery cannot be retried");
        }
        if (nextAttemptAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("Next import attempt must not precede scheduling time");
        }
    }
}
