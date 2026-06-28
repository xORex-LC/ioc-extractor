package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.export.RunSliceRetentionUseCase;
import com.iocextractor.application.port.in.export.SliceRetentionResult;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daemon lifecycle boundary for profile-scoped immutable-slice retention.
 *
 * <p>This scheduler is intentionally distinct from the leaf-file maintenance scheduler. Its
 * later lifecycle phase leaves room for a publish reconciler to start before any delivery-aware
 * guard can authorize deletion. The application use case owns selection and guard checks.
 */
public final class DaemonSliceRetentionScheduler implements SmartLifecycle {

    /** Runs after export formation; future publish lifecycle components must run before this phase. */
    public static final int PHASE = 200;

    private static final Logger log = LoggerFactory.getLogger(DaemonSliceRetentionScheduler.class);

    private final RunSliceRetentionUseCase useCase;
    private final Duration interval;
    private final Duration initialDelay;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean active;
    private ScheduledExecutorService executor;

    /** Creates a fixed-delay scheduler around the framework-free retention use case. */
    public DaemonSliceRetentionScheduler(RunSliceRetentionUseCase useCase,
                                         Duration interval,
                                         Duration initialDelay) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.interval = positive(interval, "interval");
        this.initialDelay = nonNegative(initialDelay, "initialDelay");
    }

    @Override
    public void start() {
        if (active) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ioc-slice-retention-scheduler");
            thread.setDaemon(false);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runOnce, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        active = true;
    }

    @Override
    public void stop() {
        active = false;
        if (executor != null) {
            executor.shutdownNow();
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

    /** Executes one isolated sweep; overlaps are dropped and failures are retried next tick. */
    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            SliceRetentionResult result = useCase.run();
            if (result.deleted() > 0 || result.blocked() > 0) {
                LogEvents.info(log)
                        .action(EventAction.RETENTION_SWEEP)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.IOC_ROWS, result.deleted())
                        .message("slice retention deleted " + result.deleted() + " of "
                                + result.scanned() + ", blocked " + result.blocked() + " "
                                + result.deletedByProfile())
                        .log();
            } else {
                LogEvents.debug(log)
                        .action(EventAction.RETENTION_SWEEP)
                        .outcome(EventOutcome.SUCCESS)
                        .message("slice retention sweep: nothing to delete")
                        .log();
            }
        } catch (RuntimeException failure) {
            LogEvents.error(log)
                    .action(EventAction.RETENTION_SWEEP)
                    .outcome(EventOutcome.FAILURE)
                    .message("slice retention sweep failed")
                    .log(failure);
        } finally {
            running.set(false);
        }
    }

    private Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
