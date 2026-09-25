package com.iocextractor.application.port.out.observation;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.ObservationRegistrationPurgeOutcome;
import com.iocextractor.application.observation.RegisteredObservation;

import java.time.Instant;

/** Dataframe-owned business order authority; recovery must call resume, never registerNew. */
public interface ObservationRegistrationStore {

    /** Idempotent only for the same occurrence and origin. Allocates a rank for a new occurrence. */
    RegisteredObservation registerNew(ObservationId observationId, ObservationOrigin origin);

    /** Fails closed when a journal refers to an observation missing from dataframe storage. */
    RegisteredObservation resume(ObservationId observationId, String expectedNamespace);

    /** Idempotent terminal handshake; rank remains available for referenced provenance. */
    void markTerminal(ObservationId observationId, String expectedNamespace);

    /** Removes this terminal registration only when no canonical field provenance references it. */
    boolean purgeTerminal(RegisteredObservation registration);

    /** Distinguishes provenance retention from a completed prior deletion after a crash. */
    default ObservationRegistrationPurgeOutcome purgeTerminalSafely(
            RegisteredObservation registration) {
        return purgeTerminal(registration)
                ? ObservationRegistrationPurgeOutcome.PURGED
                : ObservationRegistrationPurgeOutcome.REFERENCED;
    }

    /** Purges terminal oneshot registrations without service-journal ownership. */
    default int purgeTerminalOneshotBefore(Instant cutoff, int limit) {
        return 0;
    }
}
