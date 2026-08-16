package com.iocextractor.application.artifact.lifecycle;

import java.time.Duration;
import java.util.Objects;

/** Safety limits applied when the system UTC clock moves backwards. */
public record LifecycleClockPolicy(Duration maxBackwardSkew,
                                   Duration maxClampDuration) {

    /** Rejects policies that cannot bound clock uncertainty. */
    public LifecycleClockPolicy {
        maxBackwardSkew = requirePositive(maxBackwardSkew, "maxBackwardSkew");
        maxClampDuration = requirePositive(maxClampDuration, "maxClampDuration");
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
