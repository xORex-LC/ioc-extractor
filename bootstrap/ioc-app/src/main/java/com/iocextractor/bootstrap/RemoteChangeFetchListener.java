package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.FetchRemoteObjectsCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.sync.RemoteChangeBatchDetected;
import com.iocextractor.application.sync.RemoteFetchInFlightRegistry;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import com.iocextractor.platform.events.ControlEventObserver;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Objects;

/** Spring listener that turns remote-change facts into endpoint-keyed fetch commands. */
public final class RemoteChangeFetchListener {

    private static final String HANDLER = "RemoteChangeFetchListener";

    private final RemoteFetchUseCase fetcher;
    private final KeyedSerialExecutor executor;
    private final ControlEventObserver observer;
    private final SyncHealthState healthState;
    private final RemoteFetchInFlightRegistry inFlight;

    public RemoteChangeFetchListener(RemoteFetchUseCase fetcher,
                                     KeyedSerialExecutor executor,
                                     ControlEventObserver observer,
                                     SyncHealthState healthState,
                                     RemoteFetchInFlightRegistry inFlight) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.healthState = Objects.requireNonNull(healthState, "healthState");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
    }

    @EventListener
    public void onRemoteChangeBatchDetected(RemoteChangeBatchDetected event) {
        Objects.requireNonNull(event, "event");
        List<RemoteObject> claimed = inFlight.claim(event.objects());
        if (claimed.isEmpty()) {
            return;
        }
        WorkAdmission admission;
        try {
            admission = executor.submit(WorkKey.of(event.endpoint()), () -> {
                try (var ignored = mdc(event)) {
                    fetch(event, claimed);
                } finally {
                    inFlight.release(claimed);
                }
            });
        } catch (RuntimeException failure) {
            inFlight.release(claimed);
            healthState.recordFetchFailure(event.sourceId(), event.endpoint(), failure);
            observer.dispatchFailed(event, HANDLER, failure);
            return;
        }
        if (!admission.accepted()) {
            inFlight.release(claimed);
            IllegalStateException failure = new IllegalStateException(
                    "remote fetch work rejected for endpoint " + event.endpoint());
            observer.dispatchFailed(event, HANDLER, failure);
        }
    }

    private void fetch(RemoteChangeBatchDetected event, List<RemoteObject> objects) {
        observer.dispatching(event, HANDLER);
        try {
            RemoteFetchResult result = fetcher.fetch(new FetchRemoteObjectsCommand(
                    event.sourceId(), event.endpoint(), event.remotePath(), objects, false));
            healthState.recordFetch(event.sourceId(), event.endpoint(), result);
        } catch (RuntimeException failure) {
            healthState.recordFetchFailure(event.sourceId(), event.endpoint(), failure);
            observer.dispatchFailed(event, HANDLER, failure);
            throw failure;
        }
    }

    private MdcScope mdc(RemoteChangeBatchDetected event) {
        return MdcScope.open()
                .put(LogField.IOC_RUN_ID, event.metadata().correlationId())
                .put(LogField.IOC_EVENT_ID, event.metadata().eventId())
                .put(LogField.IOC_EVENT_TYPE, event.metadata().eventType())
                .put(LogField.IOC_EVENT_VERSION, event.metadata().eventVersion())
                .put(LogField.IOC_EVENT_CORRELATION_ID, event.metadata().correlationId())
                .put(LogField.IOC_EVENT_CAUSATION_ID, event.metadata().causationId())
                .put(LogField.IOC_EVENT_HANDLER, HANDLER)
                .put(LogField.IOC_SOURCE_ID, event.sourceId())
                .put(LogField.IOC_SYNC_ENDPOINT, event.endpoint())
                .put(LogField.IOC_SYNC_FILES, event.objects().size());
    }
}
