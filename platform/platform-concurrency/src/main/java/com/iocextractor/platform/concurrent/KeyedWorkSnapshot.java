package com.iocextractor.platform.concurrent;

import java.time.Duration;
import java.util.Objects;

/**
 * Runtime state for one keyed executor lane.
 *
 * @param key lane identity
 * @param queuedDepth waiting tasks excluding the dispatched task
 * @param running lane has a dispatched task, possibly still waiting for a worker thread
 * @param oldestAge age since submission of the oldest outstanding task, including queue wait;
 *                  wall-clock adjustments may change it and negative ages are clamped to zero
 */
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
