package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-delay daemon trigger that isolates remote fetch failures by configured source. */
public final class DaemonFetchScheduler implements SmartLifecycle {

    /** Fetch can start before export formation because it only lands files in the local inbox. */
    public static final int PHASE = 50;

    private static final Logger log = LoggerFactory.getLogger(DaemonFetchScheduler.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final List<RemoteFetchSource> sources;
    private final RemoteFetchUseCase fetcher;
    private final TransportRegistry transports;
    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile boolean active;
    private ScheduledExecutorService executor;

    /** Creates one sequential fetch scheduler over the configured source order. */
    public DaemonFetchScheduler(List<RemoteFetchSource> sources,
                                RemoteFetchUseCase fetcher,
                                TransportRegistry transports,
                                Duration interval) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.interval = positive(interval, "interval");
    }

    @Override
    public synchronized void start() {
        if (active) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ioc-sync-fetch-scheduler");
            thread.setDaemon(false);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        active = true;
    }

    /** Executes one non-overlapping cycle and keeps later sources runnable after one failure. */
    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (RemoteFetchSource source : sources) {
                attempt(source);
            }
        } finally {
            try {
                transports.closeIdle();
            } catch (RuntimeException failure) {
                LogEvents.warn(log)
                        .action(EventAction.MAINTENANCE)
                        .outcome(EventOutcome.FAILURE)
                        .message("sync transport idle cleanup failed")
                        .log(failure);
            } finally {
                running.set(false);
            }
        }
    }

    private void attempt(RemoteFetchSource source) {
        try {
            RemoteFetchResult result = fetcher.fetch(
                    new RemoteFetchCommand(Optional.of(source.sourceId()), false));
            LogEvents.info(log)
                    .action(EventAction.SYNC_FETCH_COMPLETE)
                    .outcome(result.failed() == 0 ? EventOutcome.SUCCESS : EventOutcome.FAILURE)
                    .field(LogField.IOC_SOURCE_ID, source.sourceId())
                    .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                    .field(LogField.IOC_SYNC_FILES, result.fetched())
                    .message("scheduled remote fetch completed: fetched=" + result.fetched()
                            + ", skipped=" + result.skipped() + ", failed=" + result.failed())
                    .log();
        } catch (RuntimeException failure) {
            LogEvents.error(log)
                    .action(EventAction.SYNC_FETCH_COMPLETE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_SOURCE_ID, source.sourceId())
                    .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                    .message("scheduled remote fetch source failed")
                    .log(failure);
        }
    }

    @Override
    public synchronized void stop() {
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

    @Override
    public boolean isRunning() {
        return active;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
