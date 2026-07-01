package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.sync.RemoteChangeBatchDetected;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteSourceMonitor;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Fixed-delay daemon trigger that isolates remote fetch failures by configured source. */
public final class DaemonFetchScheduler implements SmartLifecycle {

    /** Fetch can start before export formation because it only lands files in the local inbox. */
    public static final int PHASE = 50;

    private static final Logger log = LoggerFactory.getLogger(DaemonFetchScheduler.class);

    private final List<RemoteFetchSource> sources;
    private final RemoteSourceMonitor monitor;
    private final ControlEventPublisher eventPublisher;
    private final TransportRegistry transports;
    private final SyncHealthState healthState;
    private final PeriodicDaemonCycle cycle;

    /** Creates one sequential fetch scheduler over the configured source order. */
    public DaemonFetchScheduler(List<RemoteFetchSource> sources,
                                RemoteSourceMonitor monitor,
                                ControlEventPublisher eventPublisher,
                                TransportRegistry transports,
                                SyncHealthState healthState,
                                Duration interval) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.healthState = Objects.requireNonNull(healthState, "healthState");
        this.cycle = new PeriodicDaemonCycle("ioc-sync-fetch-scheduler", interval, this::runCycle);
    }

    @Override
    public void start() {
        cycle.start();
    }

    /** Executes one non-overlapping cycle and keeps later sources runnable after one failure. */
    public void runOnce() {
        cycle.runOnce();
    }

    private void runCycle() {
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
            }
        }
    }

    private void attempt(RemoteFetchSource source) {
        LogEvents.info(log)
                .action(EventAction.SYNC_FETCH_START)
                .outcome(EventOutcome.UNKNOWN)
                .field(LogField.IOC_SOURCE_ID, source.sourceId())
                .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                .message("scheduled remote fetch started")
                .log();
        try {
            List<RemoteChangeBatchDetected> events = monitor.detect(
                    new RemoteFetchCommand(Optional.of(source.sourceId()), false));
            events.forEach(eventPublisher::publish);
            int detected = events.stream().mapToInt(event -> event.objects().size()).sum();
            if (events.isEmpty()) {
                healthState.recordFetch(source.sourceId(), source.endpoint(), new RemoteFetchResult(0, 0, 0));
            }
            LogEvents.info(log)
                    .action(EventAction.SYNC_FETCH_COMPLETE)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_SOURCE_ID, source.sourceId())
                    .field(LogField.IOC_SYNC_ENDPOINT, source.endpoint())
                    .field(LogField.IOC_SYNC_FILES, detected)
                    .message("scheduled remote fetch detection completed: detected=" + detected)
                    .log();
        } catch (RuntimeException failure) {
            healthState.recordFetchFailure(source.sourceId(), source.endpoint(), failure);
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
    public void stop() {
        cycle.stop();
    }

    @Override
    public boolean isRunning() {
        return cycle.isRunning();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

}
