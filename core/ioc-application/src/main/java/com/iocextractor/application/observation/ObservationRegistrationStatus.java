package com.iocextractor.application.observation;

import java.time.Instant;
import java.util.Optional;

/** Value-free operational summary of durable observation registrations. */
public record ObservationRegistrationStatus(long pendingTotal,
                                            long pendingOneshot,
                                            Optional<Instant> oldestPendingOneshot) {

    public ObservationRegistrationStatus {
        if (pendingTotal < 0 || pendingOneshot < 0 || pendingOneshot > pendingTotal) {
            throw new IllegalArgumentException("Observation registration counts are inconsistent");
        }
        oldestPendingOneshot = oldestPendingOneshot == null
                ? Optional.empty() : oldestPendingOneshot;
    }
}
