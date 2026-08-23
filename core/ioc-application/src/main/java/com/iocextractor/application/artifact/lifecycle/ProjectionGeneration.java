package com.iocextractor.application.artifact.lifecycle;

/**
 * Monotonic generation of mutable artifact projection work.
 *
 * @param value non-negative generation; zero means no work has been requested
 */
public record ProjectionGeneration(long value) implements Comparable<ProjectionGeneration> {

    /** Validates the generation. */
    public ProjectionGeneration {
        if (value < 0) {
            throw new IllegalArgumentException("Projection generation must not be negative");
        }
    }

    /** Returns the next generation and fails on numeric overflow. */
    public ProjectionGeneration next() {
        return new ProjectionGeneration(Math.incrementExact(value));
    }

    @Override
    public int compareTo(ProjectionGeneration other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
