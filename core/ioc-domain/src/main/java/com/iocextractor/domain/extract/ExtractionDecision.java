package com.iocextractor.domain.extract;

import com.iocextractor.domain.model.IndicatorType;

import java.util.Objects;

/**
 * Pure decision for one regex match.
 *
 * @param type indicator type whose pattern matched
 * @param pattern configured pattern used for the match
 * @param span matched source span and value
 * @param status overlap-resolution outcome
 */
public record ExtractionDecision(IndicatorType type,
                                 String pattern,
                                 Span span,
                                 ExtractionDecisionStatus status) {

    public ExtractionDecision {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(status, "status");
    }
}
