package com.iocextractor.application.artifact.lifecycle;

import java.util.Map;
import java.util.Objects;

/** Aggregate outcome of one independent bounded history-retention pass. */
public record LifecycleHistoryRetentionResult(int purged,
                                              boolean moreEligible,
                                              int purgedReceipts,
                                              Map<String, Integer> purgedByArtifact) {

    public LifecycleHistoryRetentionResult {
        purgedByArtifact = Map.copyOf(Objects.requireNonNull(purgedByArtifact, "purgedByArtifact"));
        if (purged < 0 || purgedReceipts < 0
                || purgedByArtifact.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("History retention counters must not be negative");
        }
        if (Math.addExact(purgedByArtifact.values().stream().mapToInt(Integer::intValue).sum(),
                purgedReceipts) != purged) {
            throw new IllegalArgumentException("Lifecycle retention total does not match component counts");
        }
    }

    /** Compatibility constructor for history-only retention. */
    public LifecycleHistoryRetentionResult(int purged,
                                           boolean moreEligible,
                                           Map<String, Integer> purgedByArtifact) {
        this(purged, moreEligible, 0, purgedByArtifact);
    }
}
