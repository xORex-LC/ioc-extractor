package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.model.Indicator;

import java.util.List;
import java.util.Objects;

/**
 * Indicators after source attribution.
 *
 * @param outcome markers and per-indicator attribution decisions
 */
public record AttributedIndicators(AttributionOutcome outcome) {

    public AttributedIndicators {
        Objects.requireNonNull(outcome, "outcome");
    }

    /** Returns materialized attributed indicators. */
    public List<Indicator> indicators() {
        return outcome.indicators();
    }
}
