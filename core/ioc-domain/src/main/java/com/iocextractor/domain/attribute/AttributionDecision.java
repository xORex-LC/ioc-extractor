package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.SourceContext;

import java.util.Objects;
import java.util.Optional;

/** Pure attribution decision for one raw indicator. */
public record AttributionDecision(RawIndicator rawIndicator,
                                  Optional<SourceMarker> marker) {

    public AttributionDecision {
        Objects.requireNonNull(rawIndicator, "rawIndicator");
        Objects.requireNonNull(marker, "marker");
    }

    /** Materializes the attributed domain indicator. */
    public Indicator indicator() {
        return new Indicator(rawIndicator.value(), rawIndicator.type(),
                new SourceContext(marker.map(SourceMarker::label).orElse(null), null));
    }
}
