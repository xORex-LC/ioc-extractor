package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationBatchResult;

/** Resumable storage operations for the named legacy-record activation policy. */
public interface LifecycleActivationStore {

    boolean hasLegacyRecords();

    LifecycleActivationBatchResult expireLegacyBatch(
            String artifactName, EffectiveTime activationAsOf, int batchSize);
}
