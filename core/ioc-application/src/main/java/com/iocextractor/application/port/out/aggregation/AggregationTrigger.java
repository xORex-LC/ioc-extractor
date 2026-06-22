package com.iocextractor.application.port.out.aggregation;

/**
 * Driven port to request a partition aggregation run. Lets the ingest use case
 * signal "a partition is ready" so aggregation can run on the event instead of
 * (or in addition to) the periodic timer — see {@code ioc.aggregation.trigger}.
 *
 * <p>The implementation is expected to <em>coalesce</em> bursts of requests into a
 * small number of runs and never block the caller; aggregation itself is
 * idempotent (keep-first), so a coalesced or dropped request only affects latency,
 * never correctness.
 */
public interface AggregationTrigger {

    /** Signal that aggregation should run soon. Non-blocking, coalescing. */
    void request();

    /** Trigger that does nothing — used when aggregation runs on the timer only. */
    static AggregationTrigger noop() {
        return () -> { };
    }
}
