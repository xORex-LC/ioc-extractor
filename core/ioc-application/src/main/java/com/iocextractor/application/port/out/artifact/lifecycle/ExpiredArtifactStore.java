package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleDeadline;

import java.util.Optional;

/**
 * Driven port for indexed deadline discovery and bounded expiration reconciliation.
 *
 * <p>Archive, active-row delete and projection-work advancement for one batch
 * are one atomic storage transaction. Artifact revision is not advanced.
 */
public interface ExpiredArtifactStore {

    /** Returns the earliest active deadline across configured artifacts. */
    Optional<LifecycleDeadline> nearestDeadline();

    /**
     * Archives and removes at most {@code batchSize} rows due at
     * {@code cycleAsOf} from one artifact.
     *
     * @param artifactName configured artifact
     * @param cycleAsOf fixed effective time for the reconciliation cycle
     * @param batchSize positive transaction bound
     * @return batch outcome and remaining-work hint
     */
    ExpiryBatchResult expireDue(String artifactName, EffectiveTime cycleAsOf, int batchSize);
}
