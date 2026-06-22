package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.aggregation.AggregationResult;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory daemon aggregation state exposed through health indicators.
 */
public final class AggregationState {

    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.initial());

    public AggregationState(Clock clock) {
        this.clock = clock;
    }

    public void success(AggregationResult result) {
        snapshot.set(new Snapshot(Instant.now(clock), true, result, null));
    }

    public void failure(Throwable failure) {
        snapshot.set(new Snapshot(Instant.now(clock), false, null, failure.getMessage()));
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public record Snapshot(Instant updatedAt,
                           boolean successful,
                           AggregationResult result,
                           String failureMessage) {

        private static Snapshot initial() {
            return new Snapshot(null, true, null, null);
        }

        public Optional<Instant> updatedAtOptional() {
            return Optional.ofNullable(updatedAt);
        }
    }
}
