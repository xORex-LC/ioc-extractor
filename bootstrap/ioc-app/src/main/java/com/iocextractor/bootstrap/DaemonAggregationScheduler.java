package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.aggregation.AggregatePartitionsUseCase;
import com.iocextractor.application.port.in.aggregation.AggregationCommand;
import com.iocextractor.application.port.in.aggregation.AggregationResult;
import com.iocextractor.application.port.out.aggregation.AggregationTrigger;
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
 * Scheduled daemon trigger for partition aggregation, also the
 * {@link AggregationTrigger} implementation. The trigger is framework boundary
 * code; aggregation behavior remains in the application use case.
 *
 * <p>Runs are driven by {@code ioc.aggregation.trigger}: a periodic timer (when
 * {@code intervalEnabled}) and/or event-driven {@link #request()} kicks from the
 * ingest use case. A single-thread executor plus the {@code running} guard and the
 * {@code pending} coalescing flag ensure runs never overlap and bursts collapse
 * into at most one queued run.
 */
public final class DaemonAggregationScheduler implements SmartLifecycle, AggregationTrigger {

    private static final Logger log = LoggerFactory.getLogger(DaemonAggregationScheduler.class);

    private final AggregatePartitionsUseCase useCase;
    private final AggregationState state;
    private final Duration interval;
    private final Duration initialDelay;
    private final boolean intervalEnabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private volatile boolean active;

    public DaemonAggregationScheduler(AggregatePartitionsUseCase useCase,
                                      AggregationState state,
                                      Duration interval,
                                      Duration initialDelay) {
        this(useCase, state, interval, initialDelay, true);
    }

    public DaemonAggregationScheduler(AggregatePartitionsUseCase useCase,
                                      AggregationState state,
                                      Duration interval,
                                      Duration initialDelay,
                                      boolean intervalEnabled) {
        this.useCase = useCase;
        this.state = state;
        this.interval = interval;
        this.initialDelay = initialDelay;
        this.intervalEnabled = intervalEnabled;
    }

    @Override
    public void start() {
        if (active) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ioc-aggregation-scheduler");
            thread.setDaemon(false);
            return thread;
        });
        if (intervalEnabled) {
            executor.scheduleWithFixedDelay(this::runOnce,
                    initialDelay.toMillis(),
                    interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        active = true;
    }

    /**
     * Event-driven kick: request an aggregation soon without blocking the caller.
     * Coalesces a burst into at most one queued run (idempotent aggregation makes
     * a coalesced request only a latency concern). If the scheduler is not started
     * yet the request is dropped rather than run synchronously on the caller's
     * thread: the {@link AggregationTrigger} contract is non-blocking, and the
     * startup run picks the work up — the interval safety-net (when enabled) or the
     * next post-start event. In daemon mode this scheduler is up before any
     * partition (and thus any request) is produced, so the drop path is a guard,
     * not a normal case.
     */
    @Override
    public void request() {
        ScheduledExecutorService ex = this.executor;
        if (ex == null) {
            // Honor the non-blocking contract. Dropping is safe: aggregation is
            // idempotent (keep-first) and the next run catches the partition up.
            LogEvents.debug(log)
                    .message("aggregation request ignored: scheduler not started")
                    .log();
            return;
        }
        if (pending.compareAndSet(false, true)) {
            ex.execute(() -> {
                pending.set(false);
                runOnce();
            });
        }
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
            var result = useCase.aggregate(AggregationCommand.allArtifacts());
            state.success(result);
            if (hasWork(result)) {
                LogEvents.info(log)
                        .action(EventAction.AGGREGATION_COMPLETE)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.IOC_ROWS, rowsWritten(result))
                        .message("aggregation completed")
                        .log();
            } else {
                LogEvents.debug(log)
                        .action(EventAction.AGGREGATION_COMPLETE)
                        .outcome(EventOutcome.SUCCESS)
                        .message("aggregation skipped: no ready partitions")
                        .log();
            }
        } catch (RuntimeException e) {
            state.failure(e);
            LogEvents.error(log)
                    .action(EventAction.AGGREGATION_COMPLETE)
                    .outcome(EventOutcome.FAILURE)
                    .message("aggregation failed")
                    .log(e);
        } finally {
            running.set(false);
        }
    }

    private static boolean hasWork(AggregationResult result) {
        return result.sourcesProcessed() > 0
                || result.partitionsRead() > 0
                || rowsRead(result) > 0
                || rowsWritten(result) > 0
                || result.newStableIds() > 0
                || result.unchangedRows() > 0
                || result.skippedRows() > 0;
    }

    private static int rowsRead(AggregationResult result) {
        return result.rowsRead().values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int rowsWritten(AggregationResult result) {
        return result.rowsWritten().values().stream().mapToInt(Integer::intValue).sum();
    }
}
