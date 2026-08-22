package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.LifecycleHistoryRetentionResult;
import com.iocextractor.application.port.in.artifact.lifecycle.RunLifecycleHistoryRetentionUseCase;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Admission-gated history cleanup with an independent bounded cadence. */
public final class LifecycleHistoryRetentionScheduler implements SmartLifecycle {

    public static final int PHASE = 55;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final CanonicalDataAdmissionState admission;
    private final RunLifecycleHistoryRetentionUseCase retention;
    private final Duration cleanupInterval;
    private final LifecycleRuntimeObserver observer;
    private final Supplier<ScheduledExecutorService> executorFactory;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile boolean active;
    private volatile ScheduledExecutorService executor;

    public LifecycleHistoryRetentionScheduler(CanonicalDataAdmissionState admission,
                                              RunLifecycleHistoryRetentionUseCase retention,
                                              Duration cleanupInterval,
                                              LifecycleRuntimeObserver observer) {
        this(admission, retention, cleanupInterval, observer,
                LifecycleHistoryRetentionScheduler::newExecutor);
    }

    LifecycleHistoryRetentionScheduler(CanonicalDataAdmissionState admission,
                                       RunLifecycleHistoryRetentionUseCase retention,
                                       Duration cleanupInterval,
                                       LifecycleRuntimeObserver observer,
                                       Supplier<ScheduledExecutorService> executorFactory) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.cleanupInterval = requirePositive(cleanupInterval);
        this.observer = Objects.requireNonNull(observer, "observer");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    }

    @Override
    public synchronized void start() {
        if (active) {
            return;
        }
        active = true;
        admission.whenAdmitted(this::openAfterAdmission);
    }

    /** Runs one bounded cleanup pass and promptly continues an existing backlog. */
    public void runOnce() {
        if (!active || !running.compareAndSet(false, true)) {
            return;
        }
        boolean moreEligible = false;
        try {
            LifecycleHistoryRetentionResult result = retention.run();
            moreEligible = result.moreEligible();
            observer.retentionCompleted(result);
        } catch (RuntimeException failure) {
            observer.retentionFailed(failure);
        } finally {
            running.set(false);
        }
        if (moreEligible) {
            scheduleFollowUp();
        }
    }

    private synchronized void openAfterAdmission() {
        if (!active || executor != null) {
            return;
        }
        var result = admission.snapshot().result();
        if (result == null || !result.lifecycleActive()) {
            return;
        }
        executor = Objects.requireNonNull(executorFactory.get(), "executor");
        executor.scheduleWithFixedDelay(this::runOnce,
                0L, cleanupInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void scheduleFollowUp() {
        ScheduledExecutorService current = executor;
        if (!active || current == null) {
            return;
        }
        try {
            current.execute(this::runOnce);
        } catch (RejectedExecutionException ignored) {
            // Stop won the race; the next scheduled pass rediscovers durable history.
        }
    }

    @Override
    public synchronized void stop() {
        active = false;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("ioc-lifecycle-history-").daemon(true).factory());
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "cleanupInterval");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("cleanupInterval must be positive");
        }
        return value;
    }
}
