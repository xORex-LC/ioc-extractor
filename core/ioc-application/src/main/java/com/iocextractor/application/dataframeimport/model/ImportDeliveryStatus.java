package com.iocextractor.application.dataframeimport.model;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe aggregate read model for status and health surfaces.
 *
 * @param stateCounts nonterminal counts by state
 * @param headSequence current head sequence when a delivery is pending
 * @param headState current head state
 * @param headAge age of the pending head
 * @param headRetryCount failed technical attempts of the head
 * @param headRetryDelay remaining durable backoff, when deferred
 * @param headCode latest bounded safe head code
 * @param recoveryComplete whether startup reconciliation completed
 */
public record ImportDeliveryStatus(
        Map<ImportDeliveryState, Long> stateCounts,
        Optional<ImportDeliverySequence> headSequence,
        Optional<ImportDeliveryState> headState,
        Optional<Duration> headAge,
        int headRetryCount,
        Optional<Duration> headRetryDelay,
        Optional<String> headCode,
        boolean recoveryComplete) {

    /** Snapshots bounded aggregate data. */
    public ImportDeliveryStatus {
        stateCounts = Map.copyOf(Objects.requireNonNull(stateCounts, "stateCounts"));
        headSequence = Objects.requireNonNull(headSequence, "headSequence");
        headState = Objects.requireNonNull(headState, "headState");
        headAge = Objects.requireNonNull(headAge, "headAge");
        headRetryDelay = Objects.requireNonNull(headRetryDelay, "headRetryDelay");
        headCode = Objects.requireNonNull(headCode, "headCode");
        if (stateCounts.values().stream().anyMatch(count -> count == null || count < 0)) {
            throw new IllegalArgumentException("Import delivery state counts must not be negative");
        }
        if (headRetryCount < 0) {
            throw new IllegalArgumentException("Import delivery retry count must not be negative");
        }
        if (headAge.filter(Duration::isNegative).isPresent()
                || headRetryDelay.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("Import delivery status durations must not be negative");
        }
        headCode.filter(String::isBlank).ifPresent(ignored -> {
            throw new IllegalArgumentException("Import delivery head code must not be blank");
        });
        boolean hasHead = headSequence.isPresent();
        if (hasHead != headState.isPresent() || hasHead != headAge.isPresent()
                || !hasHead && (headRetryCount != 0 || headRetryDelay.isPresent() || headCode.isPresent())) {
            throw new IllegalArgumentException("Import delivery head status is incomplete");
        }
    }
}
