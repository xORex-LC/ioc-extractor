package com.iocextractor.application.cadence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fires at a fixed processing-time interval, coalescing all activity between attempts. */
public final class IntervalCadenceSource implements CadenceSource {

    private final Duration interval;
    private final Clock clock;
    private final Instant startedAt;
    private Instant completedAt;

    /** Creates an interval source whose first attempt is due one interval after construction. */
    public IntervalCadenceSource(Duration interval, Clock clock) {
        this.interval = requirePositive(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
    }

    @Override
    public synchronized boolean isDue(Instant lastActivity, Instant lastCheckpoint) {
        Instant reference = latest(startedAt, completedAt, lastCheckpoint);
        return !clock.instant().isBefore(reference.plus(interval));
    }

    @Override
    public synchronized void completed() {
        completedAt = clock.instant();
    }

    static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static Instant latest(Instant... values) {
        Instant result = null;
        for (Instant value : values) {
            if (value != null && (result == null || value.isAfter(result))) {
                result = value;
            }
        }
        return result;
    }
}
