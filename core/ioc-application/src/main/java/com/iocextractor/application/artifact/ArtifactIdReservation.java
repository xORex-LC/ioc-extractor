package com.iocextractor.application.artifact;

/** Immutable, non-reusable id range reserved for one artifact commit attempt. */
public record ArtifactIdReservation(long start, int count, ArtifactIdStrategy strategy) {

    public ArtifactIdReservation {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        java.util.Objects.requireNonNull(strategy, "strategy");
    }

    /** Returns the id at a zero-based offset inside this reservation. */
    public long idAt(int offset) {
        if (offset < 0 || offset >= count) {
            throw new IndexOutOfBoundsException(offset);
        }
        return strategy.at(start, offset);
    }
}
