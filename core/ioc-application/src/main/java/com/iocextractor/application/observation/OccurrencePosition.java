package com.iocextractor.application.observation;

/** Zero-based position of one mapped occurrence within a delivery. */
public record OccurrencePosition(long value) implements Comparable<OccurrencePosition> {

    public OccurrencePosition {
        if (value < 0) {
            throw new IllegalArgumentException("Occurrence position must be nonnegative");
        }
    }

    @Override
    public int compareTo(OccurrencePosition other) {
        return Long.compare(value, other.value);
    }
}
