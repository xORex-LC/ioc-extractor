package com.iocextractor.application.cadence;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Registry for the supported cadence strategies. */
public final class CadenceSources {

    private CadenceSources() {
    }

    /** Resolves a configured strategy without introducing framework configuration types. */
    public static CadenceSource create(String type,
                                       Duration interval,
                                       Duration quietPeriod,
                                       Duration maxCap,
                                       Clock clock) {
        String normalized = Objects.requireNonNull(type, "type").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "interval" -> new IntervalCadenceSource(interval, clock);
            case "quiet-period" -> new QuietPeriodCadenceSource(quietPeriod, maxCap, clock);
            default -> throw new IllegalArgumentException("Unsupported cadence type: " + type);
        };
    }
}
