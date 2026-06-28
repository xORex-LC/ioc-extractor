package com.iocextractor.application.cadence;

import java.time.Instant;

/**
 * Framework-free processing-time trigger for one independently checkpointed workload.
 *
 * <p>The caller supplies durable activity/checkpoint facts. Implementations may retain only
 * restart-safe scheduling state; they never perform IO or execute the workload themselves.
 */
public interface CadenceSource {

    /** Returns whether the workload should be attempted at the injected clock's current time. */
    boolean isDue(Instant lastActivity, Instant lastCheckpoint);

    /** Records a successful attempt, including a no-op attempt rejected by a downstream pre-gate. */
    void completed();
}
