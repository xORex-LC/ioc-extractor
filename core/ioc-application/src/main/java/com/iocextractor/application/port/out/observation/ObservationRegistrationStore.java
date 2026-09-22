package com.iocextractor.application.port.out.observation;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;

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
}
