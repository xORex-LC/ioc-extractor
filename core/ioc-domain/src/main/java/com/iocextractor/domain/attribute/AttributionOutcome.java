package com.iocextractor.domain.attribute;

import com.iocextractor.domain.model.Indicator;

import java.util.List;
import java.util.Objects;

/**
 * Pure source-attribution result containing discovered markers and per-indicator decisions.
 */
public record AttributionOutcome(List<SourceMarker> markers,
                                 List<AttributionDecision> decisions) {

    public AttributionOutcome {
        markers = List.copyOf(Objects.requireNonNull(markers, "markers"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
    }

    /** Returns materialized indicators in input order. */
    public List<Indicator> indicators() {
        return decisions.stream().map(AttributionDecision::indicator).toList();
    }
}
