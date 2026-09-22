package com.iocextractor.application.observation;

/** Positive dataframe-owned precedence assigned once to one delivery occurrence. */
public record ObservationOrder(long value) implements Comparable<ObservationOrder> {

    public ObservationOrder {
        if (value <= 0) {
            throw new IllegalArgumentException("Observation order must be positive");
        }
    }

    @Override
    public int compareTo(ObservationOrder other) {
        return Long.compare(value, other.value);
    }
}
