package com.iocextractor.platform.concurrent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-process keyed exclusion that preserves synchronous return and exception
 * semantics while allowing different keys to execute concurrently.
 * Reentrant calls on the same thread are supported; lock acquisition is non-fair
 * and non-interruptible. Callers nesting different keys must use a consistent key order.
 */
public final class SynchronousKeyedExecutionGuard implements KeyedExecutionGuard {

    private final ConcurrentHashMap<WorkKey, KeyState> states = new ConcurrentHashMap<>();

    @Override
    public <T> T execute(WorkKey key, Supplier<T> work) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(work, "work");
        KeyState state = states.compute(key, (ignored, current) -> {
            KeyState admitted = current == null ? new KeyState() : current;
            admitted.users++;
            return admitted;
        });

        state.lock.lock();
        Throwable primaryFailure = null;
        try {
            state.executionDepth = state.lock.getHoldCount();
            return work.get();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            state.executionDepth = state.lock.getHoldCount() - 1;
            release(key, state, primaryFailure);
        }
    }

    private void release(WorkKey key, KeyState state, Throwable primaryFailure) {
        try {
            state.lock.unlock();
            states.compute(key, (ignored, current) -> {
                if (current != state) {
                    throw new IllegalStateException("Keyed execution state changed while in use");
                }
                state.users--;
                return state.users == 0 ? null : state;
            });
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) {
                throw cleanupFailure;
            }
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public KeyedExecutionGuardSnapshot snapshot() {
        int executing = 0;
        int waiting = 0;
        for (KeyState state : states.values()) {
            int depth = state.executionDepth;
            if (depth > 0) {
                executing++;
            }
            waiting += Math.max(0, state.users - depth);
        }
        return new KeyedExecutionGuardSnapshot(states.size(), executing, waiting);
    }

    private static final class KeyState {
        private final ReentrantLock lock = new ReentrantLock();
        // Mutated only inside same-key states.compute; volatile serves snapshot visibility.
        private volatile int users;
        // Written while holding the per-key lock; nested calls are not waiting callers.
        private volatile int executionDepth;
    }
}
