package com.iocextractor.domain.extract;

import java.util.List;
import java.util.Objects;

/**
 * Pure extraction result with accepted indicators and every raw match decision.
 *
 * @param indicators accepted indicators in source order
 * @param decisions match decisions in pattern-priority order
 */
public record ExtractionOutcome(List<RawIndicator> indicators,
                                List<ExtractionDecision> decisions) {

    public ExtractionOutcome {
        indicators = List.copyOf(Objects.requireNonNull(indicators, "indicators"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
    }
}
