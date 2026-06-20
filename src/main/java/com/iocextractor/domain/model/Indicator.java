package com.iocextractor.domain.model;

import java.util.Objects;

/**
 * A fully resolved indicator: refanged, normalized value + its type + provenance.
 * Immutable domain value object — equality is by (type, value) so deduplication
 * is independent of provenance.
 */
public record Indicator(String value, IndicatorType type, SourceContext source) {

    public Indicator {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
    }

    /** Identity used for de-duplication: same value + type = same indicator. */
    public String dedupKey() {
        return type.name() + '|' + value;
    }
}
