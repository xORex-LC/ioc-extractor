package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.extract.RawIndicator;

import java.util.List;
import java.util.Objects;

/**
 * Raw indicators extracted from refanged text.
 *
 * @param text refanged source text
 * @param rawIndicators raw extracted indicators
 */
public record ExtractedIndicators(String text, List<RawIndicator> rawIndicators) {

    public ExtractedIndicators {
        Objects.requireNonNull(text, "text");
        rawIndicators = List.copyOf(Objects.requireNonNull(rawIndicators, "rawIndicators"));
    }
}
