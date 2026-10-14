package com.iocextractor.application.artifact.policy;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.OccurrencePosition;

import java.util.Objects;

/** Durable precedence of one nonempty field value within a canonical lifecycle. */
public record FieldValueOrigin(ObservationOrder admissionOrder, OccurrencePosition occurrencePosition,
                               ObservationId observationId) implements Comparable<FieldValueOrigin> {

    public FieldValueOrigin {
        Objects.requireNonNull(admissionOrder, "admissionOrder");
        Objects.requireNonNull(occurrencePosition, "occurrencePosition");
        Objects.requireNonNull(observationId, "observationId");
    }

    @Override
    public int compareTo(FieldValueOrigin other) {
        int order = admissionOrder.compareTo(other.admissionOrder);
        return order != 0 ? order : occurrencePosition.compareTo(other.occurrencePosition);
    }
}
