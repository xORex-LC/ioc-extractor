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
 * @param headAge age of the pending head
 * @param recoveryComplete whether startup reconciliation completed
 */
public record ImportDeliveryStatus(
        Map<ImportDeliveryState, Long> stateCounts,
        Optional<ImportDeliverySequence> headSequence,
        Optional<Duration> headAge,
        boolean recoveryComplete) {

    /** Snapshots bounded aggregate data. */
    public ImportDeliveryStatus {
        stateCounts = Map.copyOf(Objects.requireNonNull(stateCounts, "stateCounts"));
        headSequence = Objects.requireNonNull(headSequence, "headSequence");
        headAge = Objects.requireNonNull(headAge, "headAge");
        if (stateCounts.values().stream().anyMatch(count -> count == null || count < 0)) {
            throw new IllegalArgumentException("Import delivery state counts must not be negative");
        }
        if (headAge.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("Import delivery head age must not be negative");
        }
    }
}
