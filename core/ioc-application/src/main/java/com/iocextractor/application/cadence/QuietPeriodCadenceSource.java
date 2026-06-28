package com.iocextractor.application.cadence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Debounces durable activity while enforcing an upper bound on pending-work latency.
 *
 * <p>{@code pendingSince} is set only for the first activity newer than the checkpoint. A newer
 * activity moves the quiet deadline but not the max-cap deadline; observing the same activity
 * again therefore cannot postpone either deadline.
 */
public final class QuietPeriodCadenceSource implements CadenceSource {

    private final Duration quietPeriod;
    private final Duration maxCap;
    private final Clock clock;

    private Instant pendingSince;
    private Instant observedActivity;
    private Instant completedAt;

    /** Creates a quiet-period source; {@code maxCap} is mandatory to prevent starvation. */
    public QuietPeriodCadenceSource(Duration quietPeriod, Duration maxCap, Clock clock) {
        this.quietPeriod = IntervalCadenceSource.requirePositive(quietPeriod, "quietPeriod");
        this.maxCap = IntervalCadenceSource.requirePositive(maxCap, "maxCap");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized boolean isDue(Instant lastActivity, Instant lastCheckpoint) {
        Instant checkpoint = IntervalCadenceSource.latest(lastCheckpoint, completedAt);
        if (lastActivity == null || (checkpoint != null && !lastActivity.isAfter(checkpoint))) {
            clearPending();
            return false;
        }
        Instant now = clock.instant();
        if (pendingSince == null) {
            pendingSince = now;
            observedActivity = lastActivity;
        } else if (lastActivity.isAfter(observedActivity)) {
            observedActivity = lastActivity;
        }
        boolean quietElapsed = !now.isBefore(observedActivity.plus(quietPeriod));
        boolean capElapsed = !now.isBefore(pendingSince.plus(maxCap));
        return quietElapsed || capElapsed;
    }

    @Override
    public synchronized void completed() {
        completedAt = clock.instant();
        clearPending();
    }

    private void clearPending() {
        pendingSince = null;
        observedActivity = null;
    }
}
