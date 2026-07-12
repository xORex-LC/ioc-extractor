package com.iocextractor.application.pipeline.payload;

import java.util.List;
import java.util.Objects;

/** Indicators after one-time feature extraction and classification. */
public record ClassifiedIndicators(List<ClassifiedIndicator> indicators) {

    public ClassifiedIndicators {
        indicators = List.copyOf(Objects.requireNonNull(indicators, "indicators"));
    }
}
