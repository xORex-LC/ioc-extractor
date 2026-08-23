package com.iocextractor.application.artifact.lifecycle;

import java.time.Instant;
import java.util.Objects;

/**
 * Absolute half-open validity boundary of one canonical record lifecycle.
 *
 * <p>The record is active only while {@code asOf < validUntil}; equality is
 * already due.
 *
 * @param validUntil absolute UTC boundary
 */
public record LifecycleDeadline(Instant validUntil) implements Comparable<LifecycleDeadline> {

    /** Validates the boundary. */
    public LifecycleDeadline {
        Objects.requireNonNull(validUntil, "validUntil");
    }

    /** Returns whether the lifecycle is active at the supplied effective time. */
    public boolean isActiveAt(EffectiveTime asOf) {
        return validUntil.isAfter(Objects.requireNonNull(asOf, "asOf").value());
    }

    /** Returns whether the lifecycle is due at the supplied effective time. */
    public boolean isDueAt(EffectiveTime asOf) {
        return !isActiveAt(asOf);
    }

    @Override
    public int compareTo(LifecycleDeadline other) {
        return validUntil.compareTo(Objects.requireNonNull(other, "other").validUntil);
    }
}
