package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;

/** Driven port for bounded deletion of retained lifecycle history. */
public interface LifecycleHistoryStore {

    /** Deletes at most {@code batchSize} history rows closed at or before {@code cutoff}. */
    HistoryPurgeResult purge(String artifactName, EffectiveTime cutoff, int batchSize);

    /** One bounded purge result. */
    record HistoryPurgeResult(String artifactName, int purged, boolean moreEligible) {

        public HistoryPurgeResult {
            if (artifactName == null || artifactName.isBlank()) {
                throw new IllegalArgumentException("artifactName must not be blank");
            }
            if (purged < 0) {
                throw new IllegalArgumentException("purged must not be negative");
            }
        }
    }
}
