package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.sync.RemoteChangeBatchDetected;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteSourceMonitor;
import com.iocextractor.application.sync.RemoteTransportException;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coalesces periodic and optional push signals into source detection runs.
 *
 * <p>The coordinator is a bootstrap driving adapter. It never interprets transport-specific
 * notification payloads; each trigger causes ordinary {@link RemoteSourceMonitor} detection.</p>
 */
public final class RemoteFetchDetectionCoordinator implements RemoteFetchDetectionTrigger, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteFetchDetectionCoordinator.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final RemoteSourceMonitor monitor;
    private final ControlEventPublisher eventPublisher;
    private final TransportRegistry transports;
    private final SyncHealthState healthState;
    private final Map<String, Duration> debounceBySource;
    private final ScheduledExecutorService executor;
    private final Map<String, SourceState> states = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** Creates a coordinator with one small daemon-owned executor. */
    public RemoteFetchDetectionCoordinator(List<RemoteFetchSource> sources,
                                           RemoteSourceMonitor monitor,
                                           ControlEventPublisher eventPublisher,
                                           TransportRegistry transports,
                                           SyncHealthState healthState,
                                           Duration debounce) {
        this(sources, monitor, eventPublisher, transports, healthState,
                sources.stream().collect(java.util.stream.Collectors.toMap(
                        source -> source.sourceId(), ignored -> debounce)));
    }

    /** Creates a coordinator with per-source debounce values. */
    public RemoteFetchDetectionCoordinator(List<RemoteFetchSource> sources,
                                           RemoteSourceMonitor monitor,
                                           ControlEventPublisher eventPublisher,
                                           TransportRegistry transports,
                                           SyncHealthState healthState,
                                           Map<String, Duration> debounceBySource) {
        this(sources, monitor, eventPublisher, transports, healthState, debounceBySource,
                Executors.newScheduledThreadPool(Math.max(1, sources.size()), new java.util.concurrent.ThreadFactory() {
                    private final AtomicInteger index = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                "ioc-sync-fetch-detection-" + index.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                }));
    }

    RemoteFetchDetectionCoordinator(List<RemoteFetchSource> sources,
                                    RemoteSourceMonitor monitor,
                                    ControlEventPublisher eventPublisher,
                                    TransportRegistry transports,
                                    SyncHealthState healthState,
                                    Map<String, Duration> debounceBySource,
                                    ScheduledExecutorService executor) {
        Objects.requireNonNull(sources, "sources").forEach(source ->
                states.put(source.sourceId(), new SourceState(source)));
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.healthState = Objects.requireNonNull(healthState, "healthState");
        this.debounceBySource = Map.copyOf(Objects.requireNonNull(debounceBySource, "debounceBySource"));
        this.debounceBySource.values().forEach(duration -> requireNonNegative(duration, "debounce"));
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void trigger(RemoteFetchSource source, RemoteFetchDetectionReason reason) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
        if (closed) {
            return;
        }
        if (reason == RemoteFetchDetectionReason.PUSH) {
            healthState.recordRemoteChangeWatchSignal(source.sourceId(), source.endpoint());
        }
        SourceState state = states.computeIfAbsent(source.sourceId(), ignored -> new SourceState(source));
        state.trigger(reason);
    }

    /** Records that the optional push watch for the source is disabled. */
    public void recordWatchDisabled(RemoteFetchSource source) {
        healthState.recordRemoteChangeWatchDisabled(source.sourceId(), source.endpoint());
    }

    /** Records that the optional push watch reached active state and schedules recovery detection. */
    public void recordWatchEstablished(RemoteFetchSource source) {
        healthState.recordRemoteChangeWatchEstablished(source.sourceId(), source.endpoint());
        trigger(source, RemoteFetchDetectionReason.WATCH_ESTABLISHED);
    }

    /** Records that the optional push watch is reconnecting/degraded. */
    public void recordWatchFailure(RemoteFetchSource source, RuntimeException failure) {
        healthState.recordRemoteChangeWatchFailure(source.sourceId(), source.endpoint(), failure);
    }

    @Override
    public void close() {
        closed = true;
        for (SourceState state : states.values()) {
            state.cancelScheduled();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void runDetection(SourceState state, RemoteFetchDetectionReason reason) {
        RemoteFetchSource source = state.source;
        Thread current = Thread.currentThread();
        String originalThreadName = current.getName();
        current.setName("ioc-sync-fetch-detection-" + source.sourceId() + "-" + source.endpoint());
        long startedNanos = System.nanoTime();
        int detected = 0;
        try {
            LogEvents.debug(log)
                    .action(EventAction.SYNC_FETCH_START)
                    .outcome(EventOutcome.UNKNOWN)
                    .field(LogField.IOC_SOURCE_ID, source.sourceId())
                    .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                    .message("remote fetch detection started: reason=" + reason.name())
                    .log();
            List<RemoteChangeBatchDetected> events = monitor.detect(
                    new RemoteFetchCommand(Optional.of(source.sourceId()), false));
            detected = events.stream().mapToInt(event -> event.objects().size()).sum();
            for (RemoteChangeBatchDetected event : events) {
                publish(event);
            }
            healthState.recordFetchDetection(
                    source.sourceId(), source.endpoint(), reason.name(), detected, elapsed(startedNanos));
            logDetectionCompleted(source, reason, detected);
        } catch (RuntimeException failure) {
            healthState.recordFetchDetectionFailure(
                    source.sourceId(), source.endpoint(), reason.name(), failure, elapsed(startedNanos));
            if (!(failure instanceof RemoteTransportException)) {
                logFailure(source, reason, failure);
            }
        } finally {
            closeIdle(source);
            state.complete();
            current.setName(originalThreadName);
        }
    }

    private Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private void publish(RemoteChangeBatchDetected event) {
        try {
            eventPublisher.publish(event);
        } catch (RuntimeException failure) {
            LogEvents.warn(log)
                    .action(EventAction.EVENT_PUBLISH)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_SOURCE_ID, event.sourceId())
                    .field(LogField.IOC_SYNC_ENDPOINT, event.endpoint())
                    .message("remote change event publish failed")
                    .log(failure);
        }
    }

    private void closeIdle(RemoteFetchSource source) {
        try {
            transports.closeIdle();
        } catch (RuntimeException failure) {
            LogEvents.warn(log)
                    .action(EventAction.MAINTENANCE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_SOURCE_ID, source.sourceId())
                    .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                    .message("sync transport idle cleanup failed")
                    .log(failure);
        }
    }

    private void logDetectionCompleted(RemoteFetchSource source,
                                       RemoteFetchDetectionReason reason,
                                       int detected) {
        if (detected == 0) {
            LogEvents.debug(log)
                    .action(EventAction.SYNC_FETCH_COMPLETE)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_SOURCE_ID, source.sourceId())
                    .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                    .field(LogField.IOC_SYNC_FILES, 0)
                    .message("remote fetch detection completed: reason=" + reason.name() + " detected=0")
                    .log();
            return;
        }
        LogEvents.info(log)
                .action(EventAction.SYNC_FETCH_COMPLETE)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_SOURCE_ID, source.sourceId())
                .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                .field(LogField.IOC_SYNC_FILES, detected)
                .message("remote fetch detection completed: reason=" + reason.name() + " detected=" + detected)
                .log();
    }

    private void logFailure(RemoteFetchSource source,
                            RemoteFetchDetectionReason reason,
                            RuntimeException failure) {
        LogEvents.warn(log)
                .action(EventAction.SYNC_FETCH_COMPLETE)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_SOURCE_ID, source.sourceId())
                .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                .message("remote fetch detection failed: reason=" + reason.name())
                .log(failure);
    }

    private static Duration requireNonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private final class SourceState {
        private final RemoteFetchSource source;
        private ScheduledFuture<?> scheduled;
        private long scheduledToken;
        private long nextToken;
        private boolean running;
        private boolean rerunRequested;
        private RemoteFetchDetectionReason rerunReason = RemoteFetchDetectionReason.PUSH;

        private SourceState(RemoteFetchSource source) {
            this.source = source;
        }

        private void trigger(RemoteFetchDetectionReason reason) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (running) {
                    rerunRequested = true;
                    rerunReason = reason;
                    healthState.recordFetchDetectionCoalesced(source.sourceId(), source.endpoint());
                    return;
                }
                if (scheduled != null) {
                    if (!reason.immediate()) {
                        healthState.recordFetchDetectionCoalesced(source.sourceId(), source.endpoint());
                        return;
                    }
                    scheduled.cancel(false);
                    scheduled = null;
                }
                schedule(reason, reason.immediate() ? Duration.ZERO : debounce());
            }
        }

        private void schedule(RemoteFetchDetectionReason reason, Duration delay) {
            long token = ++nextToken;
            scheduledToken = token;
            scheduled = executor.schedule(() -> start(reason, token),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void start(RemoteFetchDetectionReason reason, long token) {
            synchronized (this) {
                if (token != scheduledToken) {
                    return;
                }
                scheduled = null;
                scheduledToken = 0L;
                if (closed) {
                    return;
                }
                if (running) {
                    rerunRequested = true;
                    rerunReason = reason;
                    return;
                }
                running = true;
            }
            runDetection(this, reason);
        }

        private void complete() {
            synchronized (this) {
                running = false;
                if (!closed && rerunRequested) {
                    RemoteFetchDetectionReason nextReason = rerunReason;
                    rerunRequested = false;
                    rerunReason = RemoteFetchDetectionReason.PUSH;
                    schedule(nextReason, debounce());
                }
            }
        }

        private void cancelScheduled() {
            synchronized (this) {
                if (scheduled != null) {
                    scheduled.cancel(false);
                    scheduled = null;
                    scheduledToken = 0L;
                }
            }
        }

        private Duration debounce() {
            return debounceBySource.getOrDefault(source.sourceId(), Duration.ZERO);
        }
    }
}
