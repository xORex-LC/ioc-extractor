package com.iocextractor.application.sync;

import java.time.Duration;

/**
 * Boundary for retry waits, allowing deterministic unit tests and adapter-specific timing.
 */
@FunctionalInterface
public interface RetrySleeper {

    /** Blocks for the supplied duration or throws if the wait cannot complete. */
    void sleep(Duration duration) throws InterruptedException;

    /** Thread-based production sleeper. */
    static RetrySleeper threadSleep() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}
