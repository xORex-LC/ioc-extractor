package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.LifecycleDeadline;
import com.iocextractor.application.port.in.artifact.lifecycle.ReconcileExpiredRecordsUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.RunLifecycleHistoryRetentionUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Admission-gated nearest-deadline worker with a periodic durable backstop. */
public final class LifecycleDeadlineScheduler implements SmartLifecycle {

    public static final int PHASE = 50;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private final CanonicalDataAdmissionState admission;
    private final ExpiredArtifactStore expiredArtifacts;
    private final ReconcileExpiredRecordsUseCase reconciliation;
    private final RunLifecycleHistoryRetentionUseCase historyRetention;
    private final Duration backstopInterval;
    private final Clock clock;
    private final LifecycleRuntimeObserver observer;
    private final Supplier<ScheduledExecutorService> executorFactory;
    private final AtomicBoolean reconciling = new AtomicBoolean();

    private volatile boolean active;
    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> deadlineTask;

    public LifecycleDeadlineScheduler(CanonicalDataAdmissionState admission,
                                      ExpiredArtifactStore expiredArtifacts,
                                      ReconcileExpiredRecordsUseCase reconciliation,
                                      RunLifecycleHistoryRetentionUseCase historyRetention,
                                      Duration backstopInterval,
                                      Clock clock,
                                      LifecycleRuntimeObserver observer) {
        this(admission, expiredArtifacts, reconciliation, historyRetention,
                backstopInterval, clock, observer, LifecycleDeadlineScheduler::newExecutor);
    }

    LifecycleDeadlineScheduler(CanonicalDataAdmissionState admission,
                               ExpiredArtifactStore expiredArtifacts,
                               ReconcileExpiredRecordsUseCase reconciliation,
                               RunLifecycleHistoryRetentionUseCase historyRetention,
                               Duration backstopInterval,
                               Clock clock,
                               LifecycleRuntimeObserver observer,
                               Supplier<ScheduledExecutorService> executorFactory) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.expiredArtifacts = Objects.requireNonNull(expiredArtifacts, "expiredArtifacts");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.historyRetention = Objects.requireNonNull(historyRetention, "historyRetention");
        this.backstopInterval = requirePositive(backstopInterval);
        this.clock = Objects.requireNonNull(clock, "clock");
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

    /** Re-queries durable deadline state and reschedules the aggregate timer. */
    public void nudge() {
        scheduleNearestDeadline();
    }

    /** Runs one idempotent reconciliation/retention attempt without overlap. */
    public void runOnce() {
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            observer.reconciliationCompleted(reconciliation.reconcile());
        } catch (RuntimeException failure) {
            observer.reconciliationFailed(failure);
        }
        try {
            observer.retentionCompleted(historyRetention.run());
        } catch (RuntimeException failure) {
            observer.retentionFailed(failure);
        } finally {
            reconciling.set(false);
            scheduleNearestDeadline();
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
                backstopInterval.toMillis(), backstopInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduleNearestDeadline();
    }

    private synchronized void scheduleNearestDeadline() {
        ScheduledExecutorService current = executor;
        if (!active || current == null || current.isShutdown()) {
            return;
        }
        Optional<LifecycleDeadline> nearest;
        try {
            nearest = expiredArtifacts.nearestDeadline();
        } catch (RuntimeException failure) {
            observer.deadlineLookupFailed(failure);
            return;
        }
        if (deadlineTask != null) {
            deadlineTask.cancel(false);
            deadlineTask = null;
        }
        if (nearest.isEmpty()) {
            return;
        }
        long delayMillis = Math.max(0L,
                Duration.between(clock.instant(), nearest.orElseThrow().validUntil()).toMillis());
        try {
            deadlineTask = current.schedule(this::runOnce, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Stop won the race; the durable deadline remains discoverable on restart.
        }
    }

    @Override
    public synchronized void stop() {
        active = false;
        if (deadlineTask != null) {
            deadlineTask.cancel(false);
            deadlineTask = null;
        }
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
                .name("ioc-lifecycle-deadline-").daemon(true).factory());
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "backstopInterval");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("backstopInterval must be positive");
        }
        return value;
    }
}
