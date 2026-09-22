package com.iocextractor.application.artifact.policy;

import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.util.Objects;

/** Durable precedence of one nonempty field value within a canonical lifecycle. */
public record FieldValueOrigin(long admissionOrder, long occurrencePosition,
                               ObservationId observationId) implements Comparable<FieldValueOrigin> {

    public FieldValueOrigin {
        if (admissionOrder <= 0 || occurrencePosition < 0) {
            throw new IllegalArgumentException("Field origin order must be positive and position nonnegative");
        }
        Objects.requireNonNull(observationId, "observationId");
    }

    @Override
    public int compareTo(FieldValueOrigin other) {
        int order = Long.compare(admissionOrder, other.admissionOrder);
        return order != 0 ? order : Long.compare(occurrencePosition, other.occurrencePosition);
    }
}
