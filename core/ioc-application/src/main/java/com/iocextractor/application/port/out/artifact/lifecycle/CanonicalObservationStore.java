package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.time.Duration;

/** Durable terminal acknowledgement for a handled or finally abandoned delivery observation. */
@FunctionalInterface
public interface CanonicalObservationStore {

    /**
     * Marks a canonical observation terminal when it exists. An attempt that
     * failed before its first canonical commit has no row and is a safe no-op.
     */
    void markTerminal(ObservationId observationId, EffectiveTime completedAt, Duration retention);
}
