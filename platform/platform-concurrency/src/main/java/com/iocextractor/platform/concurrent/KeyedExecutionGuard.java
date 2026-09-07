package com.iocextractor.platform.concurrent;

import java.util.function.Supplier;

/**
 * Executes synchronous work under an in-process per-key exclusion boundary.
 * Unlike {@link KeyedSerialExecutor}, this contract does not dispatch, queue or
 * detach work from the calling thread.
 */
public interface KeyedExecutionGuard {

    /** Executes work with mutual exclusion between threads using the same key and guard instance. */
    <T> T execute(WorkKey key, Supplier<T> work);

    /** Returns an approximate aggregate view without exposing key values; not a coordination predicate. */
    default KeyedExecutionGuardSnapshot snapshot() {
        return KeyedExecutionGuardSnapshot.empty();
    }
}
