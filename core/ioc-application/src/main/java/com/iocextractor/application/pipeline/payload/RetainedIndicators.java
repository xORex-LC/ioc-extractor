package com.iocextractor.application.pipeline.payload;

import java.util.List;
import java.util.Objects;

/**
 * Classified indicators retained for artifact preparation.
 *
 * @param extracted number of attributed indicators before de-duplication
 * @param retained classified indicators retained for sinks
 */
public record RetainedIndicators(int extracted,
                                 List<ClassifiedIndicator> retained,
                                 List<ClassifiedIndicatorOccurrence> occurrences) {

    public RetainedIndicators {
        if (extracted < 0) {
            throw new IllegalArgumentException("extracted must be non-negative");
        }
        retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
        occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
        if (retained.size() > extracted) {
            throw new IllegalArgumentException("retained size must not exceed extracted count");
        }
    }

    /** Compatibility constructor for stages that expose only legacy retained indicators. */
    public RetainedIndicators(int extracted, List<ClassifiedIndicator> retained) {
        this(extracted, retained, List.of());
    }
}
