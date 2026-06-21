package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.model.Indicator;

import java.util.List;
import java.util.Objects;

/**
 * Indicators after source attribution.
 *
 * @param indicators attributed indicators
 */
public record AttributedIndicators(List<Indicator> indicators) {

    public AttributedIndicators {
        indicators = List.copyOf(Objects.requireNonNull(indicators, "indicators"));
    }
}
