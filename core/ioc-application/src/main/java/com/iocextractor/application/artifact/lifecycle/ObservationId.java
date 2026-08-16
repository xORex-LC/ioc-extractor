package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Durable identity of one accepted delivery attempt.
 *
 * <p>The same identifier is reused only while recovering that attempt. A later
 * delivery of identical source content receives a different observation id.
 *
 * @param value opaque stable identifier
 */
public record ObservationId(String value) {

    /** Validates the durable identifier. */
    public ObservationId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Observation id must not be blank");
        }
    }
}
