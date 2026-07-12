package com.iocextractor.application.pipeline.payload;

import java.util.List;
import java.util.Objects;

/**
 * Indicators before and after de-duplication.
 *
 * @param extracted all attributed indicators
 * @param retained indicators retained for sinks
 */
public record RetainedIndicators(List<ClassifiedIndicator> extracted,
                                 List<ClassifiedIndicator> retained) {

    public RetainedIndicators {
        extracted = List.copyOf(Objects.requireNonNull(extracted, "extracted"));
        retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
    }
}
