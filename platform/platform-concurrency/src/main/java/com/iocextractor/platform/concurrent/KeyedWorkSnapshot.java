package com.iocextractor.platform.concurrent;

import java.time.Duration;
import java.util.Objects;

/** Runtime state for one keyed executor lane. */
public record KeyedWorkSnapshot(WorkKey key,
                                int queuedDepth,
                                boolean running,
                                Duration oldestAge) {

    public KeyedWorkSnapshot {
        key = Objects.requireNonNull(key, "key");
        if (queuedDepth < 0) {
            throw new IllegalArgumentException("queuedDepth must not be negative");
        }
        oldestAge = Objects.requireNonNull(oldestAge, "oldestAge");
        if (oldestAge.isNegative()) {
            oldestAge = Duration.ZERO;
        }
    }
}
