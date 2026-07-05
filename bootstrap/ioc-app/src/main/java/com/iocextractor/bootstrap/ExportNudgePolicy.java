package com.iocextractor.bootstrap;

import java.time.Duration;
import java.util.Objects;

/** Export scheduler nudge policy derived from the existing export trigger configuration. */
record ExportNudgePolicy(boolean enabled, Duration delay) {

    ExportNudgePolicy {
        delay = Objects.requireNonNull(delay, "delay");
        if (enabled && (delay.isZero() || delay.isNegative())) {
            throw new IllegalArgumentException("enabled nudge delay must be positive");
        }
    }

    static ExportNudgePolicy disabled() {
        return new ExportNudgePolicy(false, Duration.ZERO);
    }
}
