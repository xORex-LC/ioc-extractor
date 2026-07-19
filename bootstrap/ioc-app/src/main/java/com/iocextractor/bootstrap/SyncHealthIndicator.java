package com.iocextractor.bootstrap;

import com.iocextractor.application.export.SliceDescriptor;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.application.sync.CompletedSlice;
import com.iocextractor.application.sync.PublishLedgerHealthSummary;
import com.iocextractor.application.sync.PublishLedgerStatusCounts;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorSnapshot;
import com.iocextractor.platform.concurrent.KeyedWorkSnapshot;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Actuator read model for remote-sync progress, endpoint outcomes and retention blocking. */
public final class SyncHealthIndicator implements HealthIndicator {

    private static final Duration WATCH_RECONNECT_GRACE = Duration.ofSeconds(60);

    private final List<RemoteFetchSource> sources;
    private final List<PublishTarget> targets;
    private final SyncHealthState state;
    private final PublishLedger ledger;
    private final CompletedSliceCatalog catalog;
    private final Supplier<KeyedSerialExecutorSnapshot> executorSnapshot;
    private final SliceRetentionGuard retentionGuard;
    private final Clock clock;

    /** Creates a read-only health contributor over runtime snapshots and durable publish state. */
    public SyncHealthIndicator(List<RemoteFetchSource> sources,
                               List<PublishTarget> targets,
                               SyncHealthState state,
                               PublishLedger ledger,
                               CompletedSliceCatalog catalog,
                               SliceRetentionGuard retentionGuard) {
        this(sources, targets, state, ledger, catalog, KeyedSerialExecutorSnapshot::empty,
                retentionGuard, Clock.systemUTC());
    }

    /** Creates a read-only health contributor with live keyed-executor state. */
    public SyncHealthIndicator(List<RemoteFetchSource> sources,
                               List<PublishTarget> targets,
                               SyncHealthState state,
                               PublishLedger ledger,
                               CompletedSliceCatalog catalog,
                               Supplier<KeyedSerialExecutorSnapshot> executorSnapshot,
                               SliceRetentionGuard retentionGuard) {
        this(sources, targets, state, ledger, catalog, executorSnapshot, retentionGuard, Clock.systemUTC());
    }

    SyncHealthIndicator(List<RemoteFetchSource> sources,
                        List<PublishTarget> targets,
                        SyncHealthState state,
                        PublishLedger ledger,
                        CompletedSliceCatalog catalog,
                        Supplier<KeyedSerialExecutorSnapshot> executorSnapshot,
                        SliceRetentionGuard retentionGuard,
                        Clock clock) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.state = Objects.requireNonNull(state, "state");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executorSnapshot = Objects.requireNonNull(executorSnapshot, "executorSnapshot");
        this.retentionGuard = Objects.requireNonNull(retentionGuard, "retentionGuard");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Health health() {
        try {
            Set<String> configuredTargets = Set.copyOf(
                    targets.stream().map(target -> target.targetId()).toList());
            PublishLedgerHealthSummary durable = ledger.healthSummary(configuredTargets);
            PublishLedgerStatusCounts totals = durable.totals();
            Map<String, SyncHealthState.FetchSnapshot> fetches = state.fetchSnapshots();
            Map<String, SyncHealthState.FetchDetectionSnapshot> detections = state.fetchDetectionSnapshots();
            Map<String, SyncHealthState.PublishSnapshot> publishes = state.publishSnapshots();
            Map<String, SyncHealthState.KeyedExecutorSignal> executorSignals = state.keyedExecutorSignals();
            Map<String, SyncHealthState.RemoteChangeWatchSnapshot> watches = state.remoteChangeWatchSnapshots();
            long pending = totals.pending();
            long inProgress = totals.inProgress();
            long failed = totals.failed();
            long pinned = countPinnedSlices();
            SyncOperationalStatus status = overallStatus(failed, fetches, detections, publishes, executorSignals, watches);
            Health.Builder builder = switch (status) {
                case UP -> Health.up();
                case DEGRADED -> Health.status(new Status("DEGRADED"));
                case DOWN -> Health.down();
            };
            return builder
                    .withDetail("fetchSources", fetchDetails(fetches))
                    .withDetail("fetchDetection", detectionDetails(detections))
                    .withDetail("remoteChangeWatch", watchDetails(watches))
                    .withDetail("publishTargets", publishDetails(publishes))
                    .withDetail("publishPending", pending)
                    .withDetail("publishInProgress", inProgress)
                    .withDetail("publishFailed", failed)
                    .withDetail("retentionPinnedSlices", pinned)
                    .withDetail("keyedExecutor", keyedExecutorDetails(executorSignals))
                    .withDetail("endpoints", endpointDetails(fetches, detections, watches, publishes, durable.byEndpoint()))
                    .build();
        } catch (RuntimeException failure) {
            return Health.down(failure).build();
        }
    }

    private Map<String, Object> fetchDetails(Map<String, SyncHealthState.FetchSnapshot> snapshots) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (RemoteFetchSource source : sources) {
            SyncHealthState.FetchSnapshot snapshot = snapshots.get(source.sourceId());
            details.put(source.sourceId(), snapshot == null
                    ? Map.of("endpoint", source.endpoint(), "status", "NEVER_RUN")
                    : fetchDetail(snapshot));
        }
        return details;
    }

    private Map<String, Object> publishDetails(Map<String, SyncHealthState.PublishSnapshot> snapshots) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (PublishTarget target : targets) {
            SyncHealthState.PublishSnapshot snapshot = snapshots.get(target.targetId());
            details.put(target.targetId(), snapshot == null
                    ? Map.of("endpoint", target.endpoint(), "profile", target.exportProfile(),
                            "status", "NEVER_RUN")
                    : publishDetail(snapshot));
        }
        return details;
    }

    private Map<String, Object> detectionDetails(Map<String, SyncHealthState.FetchDetectionSnapshot> snapshots) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (RemoteFetchSource source : sources) {
            SyncHealthState.FetchDetectionSnapshot snapshot = snapshots.get(source.sourceId());
            details.put(source.sourceId(), snapshot == null
                    ? Map.of("endpoint", source.endpoint(), "status", "NEVER_RUN")
                    : detectionDetail(snapshot));
        }
        return details;
    }

    private Map<String, Object> detectionDetail(SyncHealthState.FetchDetectionSnapshot snapshot) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("endpoint", snapshot.endpoint());
        detail.put("lastCompletedAt", snapshot.completedAt().toString());
        detail.put("reason", snapshot.reason());
        detail.put("detectedObjects", snapshot.detectedObjects());
        detail.put("coalescedSignals", snapshot.coalescedSignals());
        detail.put("detectDurationMs", millis(snapshot.duration()));
        detail.put("status", snapshot.status().name());
        if (snapshot.error() != null) {
            detail.put("error", snapshot.error());
        }
        return detail;
    }

    private Map<String, Object> watchDetails(Map<String, SyncHealthState.RemoteChangeWatchSnapshot> snapshots) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (RemoteFetchSource source : sources) {
            SyncHealthState.RemoteChangeWatchSnapshot snapshot = snapshots.get(source.sourceId());
            details.put(source.sourceId(), snapshot == null
                    ? Map.of("endpoint", source.endpoint(), "status", "DISABLED")
                    : watchDetail(snapshot));
        }
        return details;
    }

    private Map<String, Object> watchDetail(SyncHealthState.RemoteChangeWatchSnapshot snapshot) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("endpoint", snapshot.endpoint());
        detail.put("updatedAt", snapshot.updatedAt().toString());
        detail.put("status", snapshot.status().name());
        if (snapshot.status() == RemoteChangeWatchStatus.RECONNECTING) {
            if (snapshot.reconnectingSince() != null) {
                detail.put("reconnectingSince", snapshot.reconnectingSince().toString());
                detail.put("reconnectingForMs", millis(Duration.between(snapshot.reconnectingSince(), clock.instant())));
            }
            detail.put("degradedAfterMs", WATCH_RECONNECT_GRACE.toMillis());
        }
        detail.put("signals", snapshot.signals());
        detail.put("reconnects", snapshot.reconnects());
        detail.put("reArms", snapshot.reArms());
        if (snapshot.error() != null) {
            detail.put("error", snapshot.error());
        }
        return detail;
    }

    private Map<String, Object> fetchDetail(SyncHealthState.FetchSnapshot snapshot) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("endpoint", snapshot.endpoint());
        detail.put("lastCompletedAt", snapshot.completedAt().toString());
        detail.put("fetched", snapshot.fetched());
        detail.put("skipped", snapshot.skipped());
        detail.put("failed", snapshot.failed());
        detail.put("status", snapshot.status().name());
        if (snapshot.error() != null) {
            detail.put("error", snapshot.error());
        }
        return detail;
    }

    private Map<String, Object> publishDetail(SyncHealthState.PublishSnapshot snapshot) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("endpoint", snapshot.endpoint());
        detail.put("profile", snapshot.profile());
        detail.put("lastCompletedAt", snapshot.completedAt().toString());
        detail.put("attempted", snapshot.attempted());
        detail.put("succeeded", snapshot.succeeded());
        detail.put("recovered", snapshot.recovered());
        detail.put("failed", snapshot.failed());
        detail.put("status", snapshot.status().name());
        if (snapshot.error() != null) {
            detail.put("error", snapshot.error());
        }
        return detail;
    }

    private Map<String, Object> endpointDetails(
            Map<String, SyncHealthState.FetchSnapshot> fetches,
            Map<String, SyncHealthState.FetchDetectionSnapshot> detections,
            Map<String, SyncHealthState.RemoteChangeWatchSnapshot> watches,
            Map<String, SyncHealthState.PublishSnapshot> publishes,
            Map<String, PublishLedgerStatusCounts> durableByEndpoint) {
        Set<String> endpoints = new LinkedHashSet<>();
        sources.forEach(source -> endpoints.add(source.endpoint()));
        targets.forEach(target -> endpoints.add(target.endpoint()));
        Map<String, Object> details = new LinkedHashMap<>();
        for (String endpoint : endpoints) {
            boolean hasRun = fetches.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint))
                    || detections.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint))
                    || watches.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint))
                    || publishes.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint));
            SyncOperationalStatus endpointStatus = endpointStatus(endpoint, fetches, detections, watches, publishes,
                    durableByEndpoint);
            details.put(endpoint, endpointStatus == null ? hasRun ? "UP" : "UNKNOWN" : endpointStatus.name());
        }
        return details;
    }

    private Map<String, Object> keyedExecutorDetails(
            Map<String, SyncHealthState.KeyedExecutorSignal> signals) {
        KeyedSerialExecutorSnapshot snapshot = executorSnapshot.get();
        Map<String, KeyedWorkSnapshot> liveByKey = new LinkedHashMap<>();
        for (KeyedWorkSnapshot key : snapshot.keys()) {
            liveByKey.put(key.key().value(), key);
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(liveByKey.keySet());
        keys.addAll(signals.keySet());

        List<String> runningKeys = liveByKey.values().stream()
                .filter(lane -> lane.running())
                .map(key -> key.key().value())
                .toList();
        Map<String, Object> perKey = new LinkedHashMap<>();
        for (String key : keys) {
            perKey.put(key, keyedExecutorDetail(liveByKey.get(key), signals.get(key)));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runningKeys", runningKeys);
        details.put("keys", perKey);
        return details;
    }

    private Map<String, Object> keyedExecutorDetail(
            KeyedWorkSnapshot live,
            SyncHealthState.KeyedExecutorSignal signal) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("running", live != null && live.running());
        detail.put("queueDepth", live == null ? 0 : live.queuedDepth());
        detail.put("oldestAgeMs", live == null ? 0L : millis(live.oldestAge()));
        detail.put("shedToReconcile", signal != null && signal.shedToReconcile());
        if (signal != null) {
            detail.put("lastSignalAt", signal.updatedAt().toString());
            detail.put("rejectedQueuedDepth", signal.rejectedQueuedDepth());
            detail.put("abandonedWork", signal.abandonedWork());
            if (signal.lastDispatchFailure() != null) {
                detail.put("lastDispatchFailure", signal.lastDispatchFailure());
            }
            if (signal.error() != null) {
                detail.put("error", signal.error());
            }
        }
        return detail;
    }

    private long countPinnedSlices() {
        long pinned = 0;
        for (String profile : targets.stream().map(target -> target.exportProfile()).distinct().toList()) {
            for (CompletedSlice slice : catalog.listCompleted(profile)) {
                SliceDescriptor descriptor = new SliceDescriptor(
                        slice.sliceId(), slice.profile(), slice.sliceName(), slice.manifest().createdAt());
                if (!retentionGuard.canDelete(descriptor)) {
                    pinned++;
                }
            }
        }
        return pinned;
    }

    private boolean failed(SyncHealthState.KeyedExecutorSignal signal) {
        return signal.lastDispatchFailure() != null
                || signal.error() != null;
    }

    private SyncOperationalStatus overallStatus(
            long durableFailures,
            Map<String, SyncHealthState.FetchSnapshot> fetches,
            Map<String, SyncHealthState.FetchDetectionSnapshot> detections,
            Map<String, SyncHealthState.PublishSnapshot> publishes,
            Map<String, SyncHealthState.KeyedExecutorSignal> executorSignals,
            Map<String, SyncHealthState.RemoteChangeWatchSnapshot> watches) {
        if (durableFailures > 0
                || fetches.values().stream().anyMatch(snapshot -> snapshot.status() == SyncOperationalStatus.DOWN)
                || detections.values().stream().anyMatch(snapshot -> snapshot.status() == SyncOperationalStatus.DOWN)
                || publishes.values().stream().anyMatch(snapshot -> snapshot.status() == SyncOperationalStatus.DOWN)
                || executorSignals.values().stream().anyMatch(this::failed)) {
            return SyncOperationalStatus.DOWN;
        }
        if (fetches.values().stream().anyMatch(snapshot -> snapshot.status() == SyncOperationalStatus.DEGRADED)
                || detections.values().stream().anyMatch(snapshot -> snapshot.status() == SyncOperationalStatus.DEGRADED)
                || watches.values().stream().anyMatch(this::watchDegraded)
                || publishes.values().stream().anyMatch(snapshot -> snapshot.status() == SyncOperationalStatus.DEGRADED)) {
            return SyncOperationalStatus.DEGRADED;
        }
        return SyncOperationalStatus.UP;
    }

    private SyncOperationalStatus endpointStatus(
            String endpoint,
            Map<String, SyncHealthState.FetchSnapshot> fetches,
            Map<String, SyncHealthState.FetchDetectionSnapshot> detections,
            Map<String, SyncHealthState.RemoteChangeWatchSnapshot> watches,
            Map<String, SyncHealthState.PublishSnapshot> publishes,
            Map<String, PublishLedgerStatusCounts> durableByEndpoint) {
        if (durableByEndpoint.getOrDefault(endpoint,
                new PublishLedgerStatusCounts(0, 0, 0, 0, 0)).failed() > 0
                || fetches.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint)
                && snapshot.status() == SyncOperationalStatus.DOWN)
                || detections.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint)
                && snapshot.status() == SyncOperationalStatus.DOWN)
                || publishes.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint)
                && snapshot.status() == SyncOperationalStatus.DOWN)) {
            return SyncOperationalStatus.DOWN;
        }
        if (fetches.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint)
                && snapshot.status() == SyncOperationalStatus.DEGRADED)
                || detections.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint)
                && snapshot.status() == SyncOperationalStatus.DEGRADED)
                || watches.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint)
                && watchDegraded(snapshot))
                || publishes.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint)
                && snapshot.status() == SyncOperationalStatus.DEGRADED)) {
            return SyncOperationalStatus.DEGRADED;
        }
        return null;
    }

    private boolean watchDegraded(SyncHealthState.RemoteChangeWatchSnapshot snapshot) {
        return snapshot.status() == RemoteChangeWatchStatus.RECONNECTING
                && snapshot.reconnectingSince() != null
                && Duration.between(snapshot.reconnectingSince(), clock.instant()).compareTo(WATCH_RECONNECT_GRACE) >= 0;
    }

    private long millis(Duration duration) {
        return Math.max(0L, duration.toMillis());
    }
}
