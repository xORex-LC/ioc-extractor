package com.iocextractor.platform.concurrent;

import java.time.Duration;

/**
 * Executes accepted work with per-key single-flight semantics.
 *
 * <p>Implementations are in-memory coordination primitives. They do not provide durable delivery,
 * acknowledgements, redelivery or dead-letter queues.</p>
 */
public interface KeyedSerialExecutor extends AutoCloseable {

    /** Attempts to submit one unit of work for the given key. */
    WorkAdmission submit(WorkKey key, Runnable work);

    /** Stops accepting new work and lets already accepted work drain. */
    void shutdown();

    /** Waits for drained work and worker shutdown. */
    boolean awaitTermination(Duration timeout) throws InterruptedException;

    @Override
    void close();
}
