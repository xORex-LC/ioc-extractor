package com.iocextractor.application.observation;

import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.util.Objects;

/** Stable dataframe authority reference, independent of service-ledger sequence numbers. */
public record RegisteredObservation(ObservationId observationId, String namespaceId,
                                    ObservationOrder admissionOrder, ObservationOrigin origin) {
    public RegisteredObservation {
        Objects.requireNonNull(observationId, "observationId");
        if (namespaceId == null || namespaceId.isBlank()) {
            throw new IllegalArgumentException("Registered observation requires a namespace");
        }
        Objects.requireNonNull(admissionOrder, "admissionOrder");
        Objects.requireNonNull(origin, "origin");
    }
}
