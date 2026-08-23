package com.iocextractor.application.artifact.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Read-only aggregate clock state without advancing durable lifecycle time. */
public record LifecycleClockSnapshot(LifecycleClockStatus status,
                                     Instant rawTime,
                                     EffectiveTime effectiveTime,
                                     Optional<EffectiveTime> durableHighWater,
                                     Duration backwardSkew,
                                     Duration clampAge) {

    /** Validates non-negative aggregate timing evidence. */
    public LifecycleClockSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rawTime, "rawTime");
        Objects.requireNonNull(effectiveTime, "effectiveTime");
        durableHighWater = Objects.requireNonNull(durableHighWater, "durableHighWater");
        backwardSkew = requireNonNegative(backwardSkew, "backwardSkew");
        clampAge = requireNonNegative(clampAge, "clampAge");
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
