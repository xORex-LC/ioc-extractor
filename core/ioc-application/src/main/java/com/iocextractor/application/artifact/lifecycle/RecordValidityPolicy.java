package com.iocextractor.application.artifact.lifecycle;

/**
 * Strategy that converts an accepted confirmation time into a durable absolute
 * validity boundary.
 *
 * <p>The policy does not persist, schedule or expire records. Implementations
 * must return a decision that remains active at {@code confirmationTime}.
 */
@FunctionalInterface
public interface RecordValidityPolicy {

    /**
     * Calculates the absolute boundary for one confirmation.
     *
     * @param confirmationTime transaction-level effective confirmation time
     * @return absolute validity decision
     */
    ValidityDecision decide(EffectiveTime confirmationTime);
}
