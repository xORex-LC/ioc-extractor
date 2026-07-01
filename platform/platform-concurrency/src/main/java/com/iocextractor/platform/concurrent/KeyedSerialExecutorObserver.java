package com.iocextractor.platform.concurrent;

import java.util.concurrent.RejectedExecutionException;

/**
 * Observes degradation paths of an in-memory keyed executor.
 *
 * <p>The observer is telemetry only. It must not implement retry, durable redelivery or
 * business recovery; callers still own idempotency and reconcile/backstop routing.</p>
 */
public interface KeyedSerialExecutorObserver {

    /** Called when accepted work completes normally. */
    default void completed(WorkKey key) {
    }

    /** Called when new work is rejected by admission control or shutdown. */
    default void rejected(WorkAdmission admission) {
    }

    /** Called when accepted work throws before completing normally. */
    default void failed(WorkKey key, RuntimeException failure) {
    }

    /** Called when the backing worker rejects accepted work during dispatch. */
    default void dispatchRejected(WorkKey key, int abandonedWork, RejectedExecutionException failure) {
    }
}
