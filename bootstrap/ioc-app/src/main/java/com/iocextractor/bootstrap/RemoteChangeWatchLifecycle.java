package com.iocextractor.bootstrap;

import com.iocextractor.application.port.out.sync.RemoteChangeSignalHandler;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalSource;
import com.iocextractor.application.port.out.sync.RemoteChangeWatch;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteWatchTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Starts optional remote change watches and bridges their doorbells into fetch detection. */
final class RemoteChangeWatchLifecycle implements SmartLifecycle {

    static final int PHASE = DaemonFetchScheduler.PHASE - 5;

    private static final Logger log = LoggerFactory.getLogger(RemoteChangeWatchLifecycle.class);

    private final List<RemoteFetchSource> sources;
    private final TransportRegistry transports;
    private final RemoteFetchDetectionCoordinator coordinator;
    private final Map<String, RemoteChangeWatch> watches = new LinkedHashMap<>();
    private volatile boolean running;

    RemoteChangeWatchLifecycle(List<RemoteFetchSource> sources,
                               TransportRegistry transports,
                               RemoteFetchDetectionCoordinator coordinator) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.transports = Objects.requireNonNull(transports, "transports");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            for (RemoteFetchSource source : sources) {
                RemoteChangeSignalSource signalSource = transports.changeSignals(source.endpoint())
                        .orElseThrow(() -> new IllegalStateException(
                                "change-notify is enabled for source '" + source.sourceId()
                                        + "', but endpoint '" + source.endpoint()
                                        + "' does not expose remote change signals"));
                watches.put(source.sourceId(), signalSource.watch(RemoteWatchTarget.from(source), new Handler(source)));
            }
        } catch (RuntimeException failure) {
            closeStartedWatches(failure);
            throw failure;
        }
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        RuntimeException failure = null;
        for (Map.Entry<String, RemoteChangeWatch> entry : watches.entrySet()) {
            try {
                entry.getValue().close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close remote change watches", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        for (RemoteFetchSource source : sources) {
            coordinator.recordWatchDisabled(source);
        }
        watches.clear();
        running = false;
        if (failure != null) {
            log.warn("remote change watch close failed", failure);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void closeStartedWatches(RuntimeException primaryFailure) {
        for (RemoteChangeWatch watch : watches.values()) {
            try {
                watch.close();
            } catch (RuntimeException closeFailure) {
                if (closeFailure != primaryFailure) {
                    primaryFailure.addSuppressed(closeFailure);
                }
                log.warn("remote change watch close failed after startup error", closeFailure);
            }
        }
        watches.clear();
    }

    private final class Handler implements RemoteChangeSignalHandler {
        private final RemoteFetchSource source;

        private Handler(RemoteFetchSource source) {
            this.source = source;
        }

        @Override
        public void signal() {
            coordinator.trigger(source, RemoteFetchDetectionReason.PUSH);
        }

        @Override
        public void established() {
            coordinator.recordWatchEstablished(source);
        }

        @Override
        public void failed(RuntimeException failure) {
            coordinator.recordWatchFailure(source, failure);
        }
    }
}
