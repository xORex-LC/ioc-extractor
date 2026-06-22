package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.maintenance.RetentionResult;
import com.iocextractor.application.port.in.maintenance.RunRetentionUseCase;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled daemon trigger for the retention reaper. Like
 * {@link DaemonAggregationScheduler}, the trigger is framework boundary code; the
 * retention decision and I/O stay in the application use case and its adapter.
 */
public final class DaemonMaintenanceScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DaemonMaintenanceScheduler.class);

    private final RunRetentionUseCase useCase;
    private final Duration interval;
    private final Duration initialDelay;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private volatile boolean active;

    public DaemonMaintenanceScheduler(RunRetentionUseCase useCase, Duration interval, Duration initialDelay) {
        this.useCase = useCase;
        this.interval = interval;
        this.initialDelay = initialDelay;
    }

    @Override
    public void start() {
        if (active) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ioc-maintenance-scheduler");
            thread.setDaemon(false);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runOnce,
                initialDelay.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
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

    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            RetentionResult result = useCase.run();
            if (result.reaped() > 0) {
                LogEvents.info(log)
                        .action(EventAction.RETENTION_SWEEP)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.IOC_ROWS, result.reaped())
                        .message("retention sweep reaped " + result.reaped() + " of "
                                + result.scanned() + " entries " + result.reapedByTarget())
                        .log();
            } else {
                LogEvents.debug(log)
                        .action(EventAction.RETENTION_SWEEP)
                        .outcome(EventOutcome.SUCCESS)
                        .message("retention sweep: nothing to reap")
                        .log();
            }
        } catch (RuntimeException e) {
            LogEvents.error(log)
                    .action(EventAction.RETENTION_SWEEP)
                    .outcome(EventOutcome.FAILURE)
                    .message("retention sweep failed")
                    .log(e);
        } finally {
            running.set(false);
        }
    }
}
