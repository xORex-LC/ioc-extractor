package com.iocextractor.application.artifact.lifecycle;

import java.time.Instant;
import java.util.Objects;

/**
 * One effective UTC wall-clock instant shared by a lifecycle operation.
 *
 * <p>A canonical write captures this value once after obtaining write
 * ownership. A multi-row read or reconciliation cycle likewise uses one value
 * rather than sampling the clock per row.
 *
 * @param value absolute UTC instant
 */
public record EffectiveTime(Instant value) implements Comparable<EffectiveTime> {

    /** Validates the instant. */
    public EffectiveTime {
        Objects.requireNonNull(value, "value");
    }

    /** Creates an effective-time value for an explicit instant. */
    public static EffectiveTime at(Instant value) {
        return new EffectiveTime(value);
    }

    @Override
    public int compareTo(EffectiveTime other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }
}
