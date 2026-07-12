package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;

import java.util.List;
import java.util.Objects;

/**
 * Raw indicators extracted from refanged text.
 *
 * @param text refanged source text
 * @param outcome accepted indicators and match decisions
 */
public record ExtractedIndicators(String text, ExtractionOutcome outcome) {

    public ExtractedIndicators {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** Returns accepted raw indicators. */
    public List<RawIndicator> rawIndicators() {
        return outcome.indicators();
    }
}
