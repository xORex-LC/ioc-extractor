package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects remote source identities that should be handed off to fetch execution.
 *
 * <p>The monitor performs remote listing and include/exclude filtering only. It does not download
 * files and does not own durable delivery; {@link RemoteFetchLedger} remains the idempotent receiver.
 */
public final class RemoteSourceMonitor {

    private final FileTransport transport;
    private final RemoteFetchLedger ledger;
    private final RemoteFetchInFlightRegistry inFlight;
    private final List<RemoteFetchSource> sources;
    private final int maxBatchSize;
    private final Clock clock;

    /** Creates a monitor with a bounded event batch size. */
    public RemoteSourceMonitor(FileTransport transport,
                               RemoteFetchLedger ledger,
                               RemoteFetchInFlightRegistry inFlight,
                               List<RemoteFetchSource> sources,
                               int maxBatchSize,
                               Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        this.maxBatchSize = maxBatchSize;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Detects fetchable identities for the command selection and returns control events. */
    public List<RemoteChangeBatchDetected> detect(RemoteFetchCommand command) {
        Objects.requireNonNull(command, "command");
        List<RemoteChangeBatchDetected> events = new ArrayList<>();
        for (RemoteFetchSource source : RemoteFetchSources.selected(sources, command)) {
            detectSource(source, events);
        }
        return List.copyOf(events);
    }

    private void detectSource(RemoteFetchSource source, List<RemoteChangeBatchDetected> events) {
        RemoteFetchSources.SourceMatchers matchers = RemoteFetchSources.compileMatchers(source);
        List<RemoteObject> batch = new ArrayList<>(maxBatchSize);
        for (RemoteObject object : transport.list(source.endpoint(), source.remotePath())) {
            if (!matchers.matches(object) || fetched(object) || inFlight.contains(object.identity())) {
                continue;
            }
            batch.add(object);
            if (batch.size() == maxBatchSize) {
                events.add(event(source, batch));
                batch = new ArrayList<>(maxBatchSize);
            }
        }
        if (!batch.isEmpty()) {
            events.add(event(source, batch));
        }
    }

    private boolean fetched(RemoteObject object) {
        return ledger.find(object.identity())
                .filter(record -> record.status() == RemoteFetchStatus.FETCHED)
                .isPresent();
    }

    private RemoteChangeBatchDetected event(RemoteFetchSource source, List<RemoteObject> objects) {
        String firstIdentity = objects.get(0).identity().toString();
        ControlEventMetadata metadata = ControlEventMetadata.withoutCausation(
                "remote-change-batch:" + source.sourceId() + ":" + Integer.toHexString(firstIdentity.hashCode()),
                RemoteChangeBatchDetected.EVENT_TYPE,
                RemoteChangeBatchDetected.EVENT_VERSION,
                clock.instant(),
                source.sourceId());
        return new RemoteChangeBatchDetected(
                metadata, source.sourceId(), source.endpoint(), source.remotePath(), objects);
    }
}
