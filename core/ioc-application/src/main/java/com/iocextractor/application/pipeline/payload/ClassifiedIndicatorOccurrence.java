package com.iocextractor.application.pipeline.payload;

import com.iocextractor.application.observation.OccurrencePosition;

import java.util.Objects;

/** An attributed occurrence joined to the one classification computed for its IOC key. */
public record ClassifiedIndicatorOccurrence(ClassifiedIndicator classified,
                                            OccurrencePosition position) {

    public ClassifiedIndicatorOccurrence {
        Objects.requireNonNull(classified, "classified");
        Objects.requireNonNull(position, "position");
    }
}
