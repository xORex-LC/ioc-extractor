package com.iocextractor.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small lifecycle helper for daemon reconcile loops.
 *
 * <p>The cycle owns scheduling and non-overlap only. Domain-specific logging, health and
 * failure isolation stay in the fetch/publish schedulers that use it.</p>
 */
final class PeriodicDaemonCycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final String threadName;
    private final Duration interval;
    private final Runnable task;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile boolean active;
    private ScheduledExecutorService executor;

    PeriodicDaemonCycle(String threadName, Duration interval, Runnable task) {
        this.threadName = requireText(threadName, "threadName");
        this.interval = positive(interval, "interval");
        this.task = Objects.requireNonNull(task, "task");
    }

    synchronized void start() {
        if (active) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(false);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        active = true;
    }

    void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            task.run();
        } finally {
            running.set(false);
        }
    }

    synchronized void stop() {
        active = false;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor = null;
        }
    }

    boolean isRunning() {
        return active;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
