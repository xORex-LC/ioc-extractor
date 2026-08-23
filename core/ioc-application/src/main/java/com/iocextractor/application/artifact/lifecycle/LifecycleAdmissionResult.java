package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/** Completed common admission evidence for canonical lifecycle data. */
public record LifecycleAdmissionResult(LifecycleActivationState activationState,
                                       EffectiveTime effectiveTime,
                                       int expired,
                                       int projectionsConverged) {

    public LifecycleAdmissionResult {
        Objects.requireNonNull(activationState, "activationState");
        Objects.requireNonNull(effectiveTime, "effectiveTime");
        if (expired < 0 || projectionsConverged < 0) {
            throw new IllegalArgumentException("Lifecycle admission counters must not be negative");
        }
    }

    /** Returns whether deadline scheduling is meaningful after admission. */
    public boolean lifecycleActive() {
        return activationState == LifecycleActivationState.ACTIVE;
    }
}
