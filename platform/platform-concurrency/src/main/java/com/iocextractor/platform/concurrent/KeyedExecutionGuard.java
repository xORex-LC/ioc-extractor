package com.iocextractor.platform.concurrent;

import java.util.function.Supplier;

/**
 * Executes synchronous work under an in-process per-key exclusion boundary.
 * Unlike {@link KeyedSerialExecutor}, this contract does not dispatch, queue or
 * detach work from the calling thread.
 */
public interface KeyedExecutionGuard {

    /** Executes work after all earlier work for the same key has exited. */
    <T> T execute(WorkKey key, Supplier<T> work);

    /** Returns an aggregate point-in-time view without exposing key values. */
    default KeyedExecutionGuardSnapshot snapshot() {
        return KeyedExecutionGuardSnapshot.empty();
    }
}
