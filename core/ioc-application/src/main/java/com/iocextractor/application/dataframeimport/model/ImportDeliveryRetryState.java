package com.iocextractor.application.dataframeimport.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable retry counters and eligibility without raw failure detail. */
public record ImportDeliveryRetryState(
        int attemptCount,
        Optional<Instant> nextAttemptAt,
        Optional<String> lastErrorCode) {

    /** Enforces non-negative counters and a bounded non-blank safe code. */
    public ImportDeliveryRetryState {
        nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        lastErrorCode = Objects.requireNonNull(lastErrorCode, "lastErrorCode");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("Import attempt count must not be negative");
        }
        lastErrorCode.filter(String::isBlank).ifPresent(ignored -> {
            throw new IllegalArgumentException("Import retry safe code must not be blank");
        });
    }
}
