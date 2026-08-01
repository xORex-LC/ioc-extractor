package com.iocextractor.platform.concurrent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-process keyed exclusion that preserves synchronous return and exception
 * semantics while allowing different keys to execute concurrently.
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
        state.executing = true;
        try {
            return work.get();
        } finally {
            state.executing = false;
            state.lock.unlock();
            states.compute(key, (ignored, current) -> {
                if (current != state) {
                    throw new IllegalStateException("Keyed execution state changed while in use");
                }
                state.users--;
                return state.users == 0 ? null : state;
            });
        }
    }

    @Override
    public KeyedExecutionGuardSnapshot snapshot() {
        int executing = 0;
        int waiting = 0;
        for (KeyState state : states.values()) {
            boolean running = state.executing;
            if (running) {
                executing++;
            }
            waiting += Math.max(0, state.users - (running ? 1 : 0));
        }
        return new KeyedExecutionGuardSnapshot(states.size(), executing, waiting);
    }

    private static final class KeyState {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile int users;
        private volatile boolean executing;
    }
}
