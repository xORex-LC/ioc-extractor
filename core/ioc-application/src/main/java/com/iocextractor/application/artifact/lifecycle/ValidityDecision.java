package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Storage-neutral result of applying a record-validity policy.
 *
 * @param deadline absolute boundary to persist with the lifecycle
 */
public record ValidityDecision(LifecycleDeadline deadline) {

    /** Validates the decision payload. */
    public ValidityDecision {
        Objects.requireNonNull(deadline, "deadline");
    }

    /**
     * Rejects a policy decision that would be due at the confirmation instant.
     *
     * @param confirmationTime effective confirmation time
     * @return this validated decision
     */
    public ValidityDecision requireValidAt(EffectiveTime confirmationTime) {
        if (!deadline.isActiveAt(confirmationTime)) {
            throw new IllegalArgumentException("Validity deadline must be after confirmation time");
        }
        return this;
    }
}
