package com.iocextractor.application.pipeline.payload;

import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.domain.model.Indicator;

import java.util.Objects;

/** One attributed IOC occurrence with deterministic document ordering. */
public record IndicatorOccurrence(Indicator indicator, int textPosition, int tieOrdinal) {

    public IndicatorOccurrence {
        Objects.requireNonNull(indicator, "indicator");
        if (textPosition < 0 || tieOrdinal < 0) {
            throw new IllegalArgumentException("Occurrence coordinates must be nonnegative");
        }
    }

    /** Encodes text offset and extraction tie order into one lexicographically ordered value. */
    public OccurrencePosition orderingPosition() {
        long encoded = ((long) textPosition << Integer.SIZE) | Integer.toUnsignedLong(tieOrdinal);
        return new OccurrencePosition(encoded);
    }
}
