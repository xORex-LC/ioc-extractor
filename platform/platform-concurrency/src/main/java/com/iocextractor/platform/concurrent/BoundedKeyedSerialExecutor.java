package com.iocextractor.platform.concurrent;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Keyed executor that serializes work per key while allowing different keys to run concurrently.
 *
 * <p>The queue is in-memory and intentionally non-durable. Callers must use an idempotent
 * backstop/reconcile path for correctness.</p>
 */
public final class BoundedKeyedSerialExecutor implements KeyedSerialExecutor {

    private final Object lock = new Object();
    private final ExecutorService workers;
    private final int maxQueuedPerKey;
    private final Map<WorkKey, KeyState> states = new HashMap<>();

    private boolean accepting = true;
    private int runningTasks;

    /** Creates an executor with a per-key queue bound. */
    public BoundedKeyedSerialExecutor(ExecutorService workers, int maxQueuedPerKey) {
        this.workers = Objects.requireNonNull(workers, "workers");
        if (maxQueuedPerKey < 0) {
            throw new IllegalArgumentException("maxQueuedPerKey must not be negative");
        }
        this.maxQueuedPerKey = maxQueuedPerKey;
    }

    @Override
    public WorkAdmission submit(WorkKey key, Runnable work) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(work, "work");
        Runnable firstWork = null;
        synchronized (lock) {
            if (!accepting) {
                return WorkAdmission.rejected(key, 0);
            }
            KeyState state = states.computeIfAbsent(key, ignored -> new KeyState());
            if (state.running) {
                if (state.queue.size() >= maxQueuedPerKey) {
                    return WorkAdmission.rejected(key, state.queue.size());
                }
                state.queue.add(work);
                return WorkAdmission.accepted(key, state.queue.size());
            }
            state.running = true;
            runningTasks++;
            firstWork = work;
        }
        if (!dispatch(key, firstWork)) {
            return WorkAdmission.rejected(key, 0);
        }
        return WorkAdmission.accepted(key, 0);
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            accepting = false;
            shutdownWorkersIfIdle();
        }
    }

    @Override
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return workers.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        shutdown();
    }

    private boolean dispatch(WorkKey key, Runnable work) {
        try {
            workers.execute(() -> runOne(key, work));
            return true;
        } catch (RejectedExecutionException failure) {
            cancelRunning(key);
            return false;
        }
    }

    private void runOne(WorkKey key, Runnable work) {
        try {
            work.run();
        } finally {
            runNext(key);
        }
    }

    private void runNext(WorkKey key) {
        Runnable next;
        synchronized (lock) {
            KeyState state = states.get(key);
            if (state == null) {
                return;
            }
            next = state.queue.poll();
            if (next == null) {
                state.running = false;
                runningTasks--;
                states.remove(key);
                shutdownWorkersIfIdle();
                return;
            }
        }
        if (!dispatch(key, next)) {
            synchronized (lock) {
                shutdownWorkersIfIdle();
            }
        }
    }

    private void cancelRunning(WorkKey key) {
        synchronized (lock) {
            KeyState state = states.get(key);
            if (state == null || !state.running) {
                return;
            }
            state.running = false;
            runningTasks--;
            if (state.queue.isEmpty()) {
                states.remove(key);
            }
            shutdownWorkersIfIdle();
        }
    }

    private void shutdownWorkersIfIdle() {
        if (!accepting && runningTasks == 0) {
            workers.shutdown();
        }
    }

    private static final class KeyState {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private boolean running;
    }
}
