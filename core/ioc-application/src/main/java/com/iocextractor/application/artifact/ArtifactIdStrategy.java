package com.iocextractor.application.artifact;

/** Direction of an artifact's independent public id space. */
public enum ArtifactIdStrategy {
    ASCENDING(1),
    DESCENDING(-1);

    private final int step;

    ArtifactIdStrategy(int step) {
        this.step = step;
    }

    long advance(long value, int count) {
        return value + (long) step * count;
    }

    long at(long start, int offset) {
        return start + (long) step * offset;
    }
}
