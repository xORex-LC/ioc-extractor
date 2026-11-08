package com.iocextractor.domain.model;

import java.util.Objects;

/**
 * A fully resolved indicator with its refanged, normalized value, type and provenance.
 *
 * <p>Record equality includes all three components, including {@code source}.
 * Extraction deduplication instead uses {@link #dedupKey()}, which deliberately
 * ignores provenance. Equal deduplication keys therefore do not imply equal records.
 *
 * @param value refanged, normalized indicator text; not null
 * @param type indicator vocabulary entry; not null
 * @param source attributed document provenance; not null
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
