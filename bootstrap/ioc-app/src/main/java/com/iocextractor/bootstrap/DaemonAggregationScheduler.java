package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.aggregation.AggregatePartitionsUseCase;
import com.iocextractor.application.port.in.aggregation.AggregationCommand;
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
 * Scheduled daemon trigger for partition aggregation. The trigger is framework
 * boundary code; aggregation behavior remains in the application use case.
 */
public final class DaemonAggregationScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DaemonAggregationScheduler.class);

    private final AggregatePartitionsUseCase useCase;
    private final AggregationState state;
    private final Duration interval;
    private final Duration initialDelay;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private volatile boolean active;

    public DaemonAggregationScheduler(AggregatePartitionsUseCase useCase,
                                      AggregationState state,
                                      Duration interval,
                                      Duration initialDelay) {
        this.useCase = useCase;
        this.state = state;
        this.interval = interval;
        this.initialDelay = initialDelay;
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
            LogEvents.info(log)
                    .action(EventAction.AGGREGATION_START)
                    .outcome(EventOutcome.SUCCESS)
                    .message("aggregation started")
                    .log();
            var result = useCase.aggregate(AggregationCommand.allArtifacts());
            state.success(result);
            LogEvents.info(log)
                    .action(EventAction.AGGREGATION_COMPLETE)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_ROWS, result.rowsWritten().values().stream().mapToInt(Integer::intValue).sum())
                    .message("aggregation completed")
                    .log();
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
}
