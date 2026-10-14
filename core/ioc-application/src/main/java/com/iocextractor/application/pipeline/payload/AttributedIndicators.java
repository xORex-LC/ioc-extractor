package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.model.Indicator;

import java.util.List;
import java.util.Objects;

/**
 * Indicators after source attribution.
 *
 * @param decisions repeatable attributed occurrence cursor owned by the document workspace
 */
public record AttributedIndicators(com.iocextractor.application.port.out.artifact.RowSource<com.iocextractor.domain.attribute.AttributionDecision> decisions) {

    public AttributedIndicators {
        Objects.requireNonNull(decisions, "decisions");
    }

    /** Adapts an explicitly supplied small batch, without a production fallback. */
    public AttributedIndicators(AttributionOutcome outcome) {
        this(com.iocextractor.application.port.out.artifact.RowSource.of(outcome.decisions()));
    }

    /** Returns materialized attributed indicators. */
    public List<Indicator> indicators() {
        return decisions.map(com.iocextractor.domain.attribute.AttributionDecision::indicator).snapshot();
    }
}
