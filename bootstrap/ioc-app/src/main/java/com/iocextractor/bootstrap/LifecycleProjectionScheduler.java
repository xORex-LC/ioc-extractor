package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.port.in.artifact.lifecycle.ConvergeArtifactProjectionsUseCase;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Independent admission-gated mutable-projection convergence worker. */
public final class LifecycleProjectionScheduler implements SmartLifecycle {

    public static final int PHASE = 60;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private final CanonicalDataAdmissionState admission;
    private final ConvergeArtifactProjectionsUseCase convergence;
    private final Duration backstopInterval;
    private final LifecycleRuntimeObserver observer;
    private final Supplier<ScheduledExecutorService> executorFactory;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean nudgeScheduled = new AtomicBoolean();

    private volatile boolean active;
    private volatile ScheduledExecutorService executor;

    public LifecycleProjectionScheduler(CanonicalDataAdmissionState admission,
                                        ConvergeArtifactProjectionsUseCase convergence,
                                        Duration backstopInterval,
                                        LifecycleRuntimeObserver observer) {
        this(admission, convergence, backstopInterval, observer,
                LifecycleProjectionScheduler::newExecutor);
    }

    LifecycleProjectionScheduler(CanonicalDataAdmissionState admission,
                                 ConvergeArtifactProjectionsUseCase convergence,
                                 Duration backstopInterval,
                                 LifecycleRuntimeObserver observer,
                                 Supplier<ScheduledExecutorService> executorFactory) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.convergence = Objects.requireNonNull(convergence, "convergence");
        this.backstopInterval = requirePositive(backstopInterval);
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

    /** Coalesces any number of lossy event hints into one durable-state pass. */
    public void nudge() {
        ScheduledExecutorService current = executor;
        if (!active || current == null || !nudgeScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            current.execute(() -> {
                nudgeScheduled.set(false);
                runOnce();
            });
        } catch (RejectedExecutionException ignored) {
            nudgeScheduled.set(false);
        }
    }

    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            observer.projectionCompleted(convergence.convergePending());
        } catch (RuntimeException failure) {
            observer.projectionFailed(failure);
        } finally {
            running.set(false);
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
        nudge();
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
                .name("ioc-lifecycle-projection-").daemon(true).factory());
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "backstopInterval");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("backstopInterval must be positive");
        }
        return value;
    }
}
