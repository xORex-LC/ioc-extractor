package com.iocextractor.platform.concurrent;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
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
    private final KeyedSerialExecutorObserver observer;
    private final Clock clock;
    private final Map<WorkKey, KeyState> states = new HashMap<>();

    private boolean accepting = true;
    private int runningTasks;

    /** Creates an executor with a per-key queue bound. */
    public BoundedKeyedSerialExecutor(ExecutorService workers, int maxQueuedPerKey) {
        this(workers, maxQueuedPerKey, NoopKeyedSerialExecutorObserver.INSTANCE);
    }

    /** Creates an executor with a per-key queue bound and degradation observer. */
    public BoundedKeyedSerialExecutor(ExecutorService workers,
                                      int maxQueuedPerKey,
                                      KeyedSerialExecutorObserver observer) {
        this(workers, maxQueuedPerKey, observer, Clock.systemUTC());
    }

    /** Creates an executor with an explicit clock for deterministic snapshots. */
    public BoundedKeyedSerialExecutor(ExecutorService workers,
                                      int maxQueuedPerKey,
                                      KeyedSerialExecutorObserver observer,
                                      Clock clock) {
        this.workers = Objects.requireNonNull(workers, "workers");
        if (maxQueuedPerKey < 0) {
            throw new IllegalArgumentException("maxQueuedPerKey must not be negative");
        }
        this.maxQueuedPerKey = maxQueuedPerKey;
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public WorkAdmission submit(WorkKey key, Runnable work) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(work, "work");
        Instant submittedAt = clock.instant();
        Runnable firstWork = null;
        WorkAdmission rejected = null;
        synchronized (lock) {
            if (!accepting) {
                rejected = WorkAdmission.rejected(key, 0);
            } else {
                KeyState state = states.computeIfAbsent(key, ignored -> new KeyState());
                if (state.running) {
                    if (state.queue.size() >= maxQueuedPerKey) {
                        rejected = WorkAdmission.rejected(key, state.queue.size());
                    } else {
                        state.queue.add(new QueuedWork(work, submittedAt));
                        return WorkAdmission.accepted(key, state.queue.size());
                    }
                } else {
                    state.running = true;
                    state.runningSince = submittedAt;
                    runningTasks++;
                    firstWork = work;
                }
            }
        }
        if (rejected != null) {
            observeRejected(rejected);
            return rejected;
        }
        if (!dispatch(key, Objects.requireNonNull(firstWork, "firstWork"))) {
            // dispatch() already signalled observer.dispatchRejected on its failure path; do not
            // also fire observer.rejected here (that channel is for admission/shutdown rejections).
            return WorkAdmission.rejected(key, 0);
        }
        return WorkAdmission.accepted(key, 0);
    }

    @Override
    public KeyedSerialExecutorSnapshot snapshot() {
        Instant now = clock.instant();
        synchronized (lock) {
            var snapshots = new ArrayList<KeyedWorkSnapshot>(states.size());
            for (Map.Entry<WorkKey, KeyState> entry : states.entrySet()) {
                KeyState state = entry.getValue();
                snapshots.add(new KeyedWorkSnapshot(
                        entry.getKey(),
                        state.queue.size(),
                        state.running,
                        Duration.between(oldestAcceptedAt(state), now)));
            }
            snapshots.sort(Comparator.comparing(snapshot -> snapshot.key().value()));
            return new KeyedSerialExecutorSnapshot(snapshots);
        }
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
            int abandonedWork = abandonKey(key, 1);
            observeDispatchRejected(key, abandonedWork, failure);
            return false;
        }
    }

    private void runOne(WorkKey key, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            observeFailed(key, failure);
        } finally {
            runNext(key);
        }
    }

    private void runNext(WorkKey key) {
        QueuedWork next;
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
            state.runningSince = clock.instant();
        }
        if (!dispatch(key, next.work())) {
            shutdownIfIdle();
        }
    }

    private int abandonKey(WorkKey key, int currentWork) {
        synchronized (lock) {
            KeyState state = states.get(key);
            if (state == null || !state.running) {
                return currentWork;
            }
            int abandonedWork = currentWork + state.queue.size();
            state.running = false;
            runningTasks--;
            states.remove(key);
            shutdownWorkersIfIdle();
            return abandonedWork;
        }
    }

    private void shutdownIfIdle() {
        synchronized (lock) {
            shutdownWorkersIfIdle();
        }
    }

    private void shutdownWorkersIfIdle() {
        if (!accepting && runningTasks == 0) {
            workers.shutdown();
        }
    }

    private void observeRejected(WorkAdmission admission) {
        try {
            observer.rejected(admission);
        } catch (RuntimeException ignored) {
            // Observability hooks must not change executor admission semantics.
        }
    }

    private void observeFailed(WorkKey key, RuntimeException failure) {
        try {
            observer.failed(key, failure);
        } catch (RuntimeException ignored) {
            // Observability hooks must not block FIFO progress for the key.
        }
    }

    private void observeDispatchRejected(WorkKey key,
                                         int abandonedWork,
                                         RejectedExecutionException failure) {
        try {
            observer.dispatchRejected(key, abandonedWork, failure);
        } catch (RuntimeException ignored) {
            // Accepted in-memory work is recovered by callers through reconcile/backstop paths.
        }
    }

    private static final class KeyState {
        private final Queue<QueuedWork> queue = new ArrayDeque<>();
        private Instant runningSince;
        private boolean running;
    }

    private Instant oldestAcceptedAt(KeyState state) {
        if (state.runningSince != null) {
            return state.runningSince;
        }
        QueuedWork queued = state.queue.peek();
        if (queued != null) {
            return queued.submittedAt();
        }
        return clock.instant();
    }

    private record QueuedWork(Runnable work, Instant submittedAt) {

        private QueuedWork {
            work = Objects.requireNonNull(work, "work");
            submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        }
    }
}
