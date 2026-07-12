package com.iocextractor.application.artifact;

import java.util.Objects;

/**
 * Thread-safe owner of one artifact id space.
 *
 * <p>Reservation advances monotonically before storage I/O. A failed commit does
 * not return its range, preventing ambiguous id reuse on retry.</p>
 */
public final class ArtifactIdSequence {

    private final ArtifactIdStrategy strategy;
    private long next;

    /**
     * Creates a sequence at the next available public id.
     *
     * @param strategy ascending or descending allocation
     * @param start first id available for reservation
     */
    public ArtifactIdSequence(ArtifactIdStrategy strategy, long start) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.next = start;
    }

    /** Atomically reserves a non-overlapping range and advances this sequence. */
    public synchronized ArtifactIdReservation reserve(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        var reservation = new ArtifactIdReservation(next, count, strategy);
        next = strategy.advance(next, count);
        return reservation;
    }
}
