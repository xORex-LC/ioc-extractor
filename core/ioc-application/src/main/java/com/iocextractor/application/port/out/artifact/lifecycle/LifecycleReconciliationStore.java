package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconcileCycleId;

/** Driven port for durable aggregate reconciliation-cycle evidence. */
public interface LifecycleReconciliationStore {

    /** Marks any process-abandoned started cycles as failed. */
    int failInterrupted(EffectiveTime recoveredAt, String failureCode);

    /** Starts one new reconciliation cycle at a fixed effective boundary. */
    LifecycleReconcileCycleId start(EffectiveTime cycleAsOf);

    /** Adds one committed archive/delete batch to advisory in-progress counters. */
    void recordBatch(LifecycleReconcileCycleId cycleId, int expired);

    /** Publishes exact terminal aggregate counters for a completed cycle. */
    void complete(LifecycleReconcileCycleId cycleId,
                  EffectiveTime completedAt,
                  int expired,
                  int affectedArtifacts);

    /** Marks a failed cycle without altering already committed expiry transactions. */
    void fail(LifecycleReconcileCycleId cycleId, EffectiveTime failedAt, String failureCode);
}
