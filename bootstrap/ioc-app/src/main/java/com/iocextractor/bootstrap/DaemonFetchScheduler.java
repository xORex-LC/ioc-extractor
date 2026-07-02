package com.iocextractor.bootstrap;

import com.iocextractor.application.sync.RemoteFetchSource;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Fixed-delay daemon trigger that isolates remote fetch failures by configured source. */
public final class DaemonFetchScheduler implements SmartLifecycle {

    /** Fetch can start before export formation because it only lands files in the local inbox. */
    public static final int PHASE = 50;

    private final List<RemoteFetchSource> sources;
    private final RemoteFetchDetectionTrigger detection;
    private final PeriodicDaemonCycle cycle;

    /** Creates one sequential fetch scheduler over the configured source order. */
    public DaemonFetchScheduler(List<RemoteFetchSource> sources,
                                RemoteFetchDetectionTrigger detection,
                                Duration interval) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.detection = Objects.requireNonNull(detection, "detection");
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
        for (RemoteFetchSource source : sources) {
            detection.trigger(source, RemoteFetchDetectionReason.PERIODIC);
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
