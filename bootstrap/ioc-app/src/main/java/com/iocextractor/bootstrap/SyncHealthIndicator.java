package com.iocextractor.bootstrap;

import com.iocextractor.application.export.SliceDescriptor;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.application.sync.CompletedSlice;
import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishStatus;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorSnapshot;
import com.iocextractor.platform.concurrent.KeyedWorkSnapshot;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

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

    private final List<RemoteFetchSource> sources;
    private final List<PublishTarget> targets;
    private final SyncHealthState state;
    private final PublishLedger ledger;
    private final CompletedSliceCatalog catalog;
    private final Supplier<KeyedSerialExecutorSnapshot> executorSnapshot;
    private final SliceRetentionGuard retentionGuard;

    /** Creates a read-only health contributor over runtime snapshots and durable publish state. */
    public SyncHealthIndicator(List<RemoteFetchSource> sources,
                               List<PublishTarget> targets,
                               SyncHealthState state,
                               PublishLedger ledger,
                               CompletedSliceCatalog catalog,
                               SliceRetentionGuard retentionGuard) {
        this(sources, targets, state, ledger, catalog, KeyedSerialExecutorSnapshot::empty, retentionGuard);
    }

    /** Creates a read-only health contributor with live keyed-executor state. */
    public SyncHealthIndicator(List<RemoteFetchSource> sources,
                               List<PublishTarget> targets,
                               SyncHealthState state,
                               PublishLedger ledger,
                               CompletedSliceCatalog catalog,
                               Supplier<KeyedSerialExecutorSnapshot> executorSnapshot,
                               SliceRetentionGuard retentionGuard) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.state = Objects.requireNonNull(state, "state");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executorSnapshot = Objects.requireNonNull(executorSnapshot, "executorSnapshot");
        this.retentionGuard = Objects.requireNonNull(retentionGuard, "retentionGuard");
    }

    @Override
    public Health health() {
        try {
            Set<String> configuredTargets = Set.copyOf(
                    targets.stream().map(target -> target.targetId()).toList());
            List<PublishRecord> records = ledger.findAll().stream()
                    .filter(record -> configuredTargets.contains(record.targetId()))
                    .toList();
            Map<String, SyncHealthState.FetchSnapshot> fetches = state.fetchSnapshots();
            Map<String, SyncHealthState.PublishSnapshot> publishes = state.publishSnapshots();
            Map<String, SyncHealthState.KeyedExecutorSignal> executorSignals = state.keyedExecutorSignals();
            long pending = records.stream().filter(record -> record.status() == PublishStatus.PENDING).count();
            long inProgress = records.stream().filter(record -> record.status() == PublishStatus.IN_PROGRESS).count();
            long failed = records.stream().filter(record -> record.status() == PublishStatus.FAILED).count();
            long pinned = countPinnedSlices();
            boolean unhealthy = failed > 0
                    || fetches.values().stream().anyMatch(this::failed)
                    || publishes.values().stream().anyMatch(this::failed)
                    || executorSignals.values().stream().anyMatch(this::failed);

            Health.Builder builder = unhealthy ? Health.down() : Health.up();
            return builder
                    .withDetail("fetchSources", fetchDetails(fetches))
                    .withDetail("publishTargets", publishDetails(publishes))
                    .withDetail("publishPending", pending)
                    .withDetail("publishInProgress", inProgress)
                    .withDetail("publishFailed", failed)
                    .withDetail("retentionPinnedSlices", pinned)
                    .withDetail("keyedExecutor", keyedExecutorDetails(executorSignals))
                    .withDetail("endpoints", endpointDetails(fetches, publishes, records))
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

    private Map<String, Object> fetchDetail(SyncHealthState.FetchSnapshot snapshot) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("endpoint", snapshot.endpoint());
        detail.put("lastCompletedAt", snapshot.completedAt().toString());
        detail.put("fetched", snapshot.fetched());
        detail.put("skipped", snapshot.skipped());
        detail.put("failed", snapshot.failed());
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
        detail.put("pending", snapshot.pending());
        detail.put("succeeded", snapshot.succeeded());
        detail.put("failed", snapshot.failed());
        detail.put("abandoned", snapshot.abandoned());
        if (snapshot.error() != null) {
            detail.put("error", snapshot.error());
        }
        return detail;
    }

    private Map<String, Object> endpointDetails(
            Map<String, SyncHealthState.FetchSnapshot> fetches,
            Map<String, SyncHealthState.PublishSnapshot> publishes,
            List<PublishRecord> records) {
        Set<String> endpoints = new LinkedHashSet<>();
        sources.forEach(source -> endpoints.add(source.endpoint()));
        targets.forEach(target -> endpoints.add(target.endpoint()));
        Map<String, Object> details = new LinkedHashMap<>();
        for (String endpoint : endpoints) {
            boolean hasRun = fetches.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint))
                    || publishes.values().stream().anyMatch(snapshot -> snapshot.endpoint().equals(endpoint));
            boolean endpointFailed = fetches.values().stream()
                    .filter(snapshot -> snapshot.endpoint().equals(endpoint)).anyMatch(this::failed)
                    || publishes.values().stream()
                    .filter(snapshot -> snapshot.endpoint().equals(endpoint)).anyMatch(this::failed)
                    || records.stream().anyMatch(record -> record.endpoint().equals(endpoint)
                    && record.status() == PublishStatus.FAILED);
            details.put(endpoint, endpointFailed ? "DOWN" : hasRun ? "UP" : "UNKNOWN");
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
                .filter(KeyedWorkSnapshot::running)
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

    private boolean failed(SyncHealthState.FetchSnapshot snapshot) {
        return snapshot.failed() > 0 || snapshot.error() != null;
    }

    private boolean failed(SyncHealthState.PublishSnapshot snapshot) {
        return snapshot.failed() > 0 || snapshot.error() != null;
    }

    private boolean failed(SyncHealthState.KeyedExecutorSignal signal) {
        return signal.lastDispatchFailure() != null
                || signal.error() != null;
    }

    private long millis(Duration duration) {
        return Math.max(0L, duration.toMillis());
    }
}
