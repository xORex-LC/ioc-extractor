package com.iocextractor.application.sync;

import java.time.Duration;
import java.util.Objects;

/**
 * Numeric micro-retry policy for transport operations.
 *
 * <p>The policy controls attempt counts and backoff timing only. Whether a remote error is
 * retryable is owned by {@link RemoteErrorKind#disposition()}.
 */
public record RetryPolicy(int maxAttempts,
                          Duration backoff,
                          double multiplier,
                          Duration maxBackoff,
                          boolean jitter) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        backoff = positive(backoff, "backoff");
        if (multiplier < 1.0d) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
        maxBackoff = positive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(backoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be greater than or equal to backoff");
        }
    }

    /** Returns the base delay for the one-based attempt that just failed. */
    Duration delayAfterAttempt(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt must be positive");
        }
        double scaled = backoff.toNanos() * Math.pow(multiplier, failedAttempt - 1.0d);
        long nanos = scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(scaled);
        Duration candidate = Duration.ofNanos(nanos);
        return candidate.compareTo(maxBackoff) > 0 ? maxBackoff : candidate;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
