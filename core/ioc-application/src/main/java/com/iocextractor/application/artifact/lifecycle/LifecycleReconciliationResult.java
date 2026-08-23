package com.iocextractor.application.artifact.lifecycle;

import java.util.List;
import java.util.Objects;

/** Aggregate outcome of one bounded, multi-artifact expiry reconciliation pass. */
public record LifecycleReconciliationResult(LifecycleReconcileCycleId cycleId,
                                            EffectiveTime cycleAsOf,
                                            int expired,
                                            int batches,
                                            List<String> affectedArtifacts) {

    /** Copies aggregate evidence and rejects inconsistent counters. */
    public LifecycleReconciliationResult {
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(cycleAsOf, "cycleAsOf");
        affectedArtifacts = List.copyOf(Objects.requireNonNull(affectedArtifacts, "affectedArtifacts"));
        if (expired < 0 || batches < 0) {
            throw new IllegalArgumentException("Lifecycle reconciliation counters must not be negative");
        }
        if (expired == 0 && !affectedArtifacts.isEmpty()) {
            throw new IllegalArgumentException("Unaffected reconciliation cannot name artifacts");
        }
    }

    /** Returns whether canonical membership changed during this pass. */
    public boolean changedMembership() {
        return expired > 0;
    }
}
