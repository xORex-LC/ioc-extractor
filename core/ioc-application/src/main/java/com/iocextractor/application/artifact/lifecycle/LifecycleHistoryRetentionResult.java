package com.iocextractor.application.artifact.lifecycle;

import java.util.Map;
import java.util.Objects;

/** Aggregate outcome of one independent bounded history-retention pass. */
public record LifecycleHistoryRetentionResult(int purged,
                                              boolean moreEligible,
                                              Map<String, Integer> purgedByArtifact) {

    public LifecycleHistoryRetentionResult {
        purgedByArtifact = Map.copyOf(Objects.requireNonNull(purgedByArtifact, "purgedByArtifact"));
        if (purged < 0 || purgedByArtifact.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("History retention counters must not be negative");
        }
        if (purgedByArtifact.values().stream().mapToInt(Integer::intValue).sum() != purged) {
            throw new IllegalArgumentException("History retention total does not match artifact counts");
        }
    }
}
