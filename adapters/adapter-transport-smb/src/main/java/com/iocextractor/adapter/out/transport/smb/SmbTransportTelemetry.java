package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.sync.RemoteErrorKind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe adapter-owned telemetry for established SMB resources and failures.
 *
 * <p>State is isolated by an internal owner identity so recovery of one change-notify
 * worker cannot hide an outstanding capacity failure from another worker. Public
 * snapshots aggregate owners into bounded endpoint/role dimensions.
 */
public final class SmbTransportTelemetry {

    private final Clock clock;
    private final Map<OwnerKey, OwnerState> owners = new ConcurrentHashMap<>();

    /** Creates telemetry using the system UTC clock. */
    public SmbTransportTelemetry() {
        this(Clock.systemUTC());
    }

    /** Creates telemetry using an explicit clock. */
    public SmbTransportTelemetry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Lease sessionOpened(String endpoint, Role role, String owner) {
        OwnerState state = state(endpoint, role, owner);
        state.successfulOpens.increment();
        state.activeResources.incrementAndGet();
        state.recordSuccess(clock.instant());
        return new Lease(state);
    }

    void recordOpenFailure(String endpoint, Role role, String owner, RemoteErrorKind kind) {
        OwnerState state = state(endpoint, role, owner);
        state.openFailures.increment();
        state.recordFailure(kind, clock.instant());
    }

    void recordOperationSuccess(String endpoint, Role role, String owner) {
        state(endpoint, role, owner).recordSuccess(clock.instant());
    }

    void recordOperationFailure(String endpoint, Role role, String owner, RemoteErrorKind kind) {
        OwnerState state = state(endpoint, role, owner);
        state.operationFailures.increment();
        state.recordFailure(kind, clock.instant());
    }

    /** Returns a weakly consistent, endpoint/role-sorted aggregate snapshot. */
    public List<Snapshot> snapshot() {
        Map<MetricKey, Aggregate> aggregates = new HashMap<>();
        owners.forEach((key, state) -> aggregates
                .computeIfAbsent(new MetricKey(key.endpoint(), key.role()), ignored -> new Aggregate())
                .add(state));
        List<Snapshot> snapshots = new ArrayList<>(aggregates.size());
        aggregates.forEach((key, aggregate) -> snapshots.add(aggregate.snapshot(key)));
        snapshots.sort(Comparator.comparing(Snapshot::endpoint).thenComparing(Snapshot::role));
        return List.copyOf(snapshots);
    }

    /** Returns one aggregate or an all-zero snapshot when the role has not run yet. */
    public Snapshot snapshot(String endpoint, Role role) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(role, "role");
        return snapshot().stream()
                .filter(value -> value.endpoint().equals(endpoint) && value.role() == role)
                .findFirst()
                .orElseGet(() -> Snapshot.empty(endpoint, role));
    }

    private OwnerState state(String endpoint, Role role, String owner) {
        OwnerKey key = new OwnerKey(endpoint, role, owner);
        return owners.computeIfAbsent(key, ignored -> new OwnerState());
    }

    /** Stable low-cardinality role of one application-owned SMB session. */
    public enum Role {
        POOLED_TRANSPORT("pooled_transport"),
        CHANGE_NOTIFY("change_notify");

        private final String tagValue;

        Role(String tagValue) {
            this.tagValue = tagValue;
        }

        /** Returns the stable metrics tag value. */
        public String tagValue() {
            return tagValue;
        }
    }

    /** Aggregated established-resource and failure state for one endpoint and role. */
    public record Snapshot(
            String endpoint,
            Role role,
            int activeConnections,
            int activeSessions,
            int activeTreeConnections,
            long successfulOpens,
            long openFailures,
            long operationFailures,
            long resourceExhaustions,
            boolean resourceConstrained,
            Instant lastSuccessAt,
            Instant lastResourceExhaustionAt) {

        private static Snapshot empty(String endpoint, Role role) {
            return new Snapshot(endpoint, role, 0, 0, 0,
                    0L, 0L, 0L, 0L, false, null, null);
        }
    }

    private record OwnerKey(String endpoint, Role role, String owner) {

        private OwnerKey {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("endpoint must not be blank");
            }
            role = Objects.requireNonNull(role, "role");
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("owner must not be blank");
            }
        }
    }

    private record MetricKey(String endpoint, Role role) {
    }

    static final class Lease implements AutoCloseable {

        private final OwnerState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(OwnerState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.activeResources.decrementAndGet();
            }
        }
    }

    private static final class OwnerState {

        private final AtomicInteger activeResources = new AtomicInteger();
        private final LongAdder successfulOpens = new LongAdder();
        private final LongAdder openFailures = new LongAdder();
        private final LongAdder operationFailures = new LongAdder();
        private final LongAdder resourceExhaustions = new LongAdder();
        private final AtomicBoolean resourceConstrained = new AtomicBoolean();
        private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
        private final AtomicReference<Instant> lastResourceExhaustionAt = new AtomicReference<>();

        private void recordSuccess(Instant now) {
            lastSuccessAt.set(now);
            resourceConstrained.set(false);
        }

        private void recordFailure(RemoteErrorKind kind, Instant now) {
            Objects.requireNonNull(kind, "kind");
            if (kind == RemoteErrorKind.RESOURCE_EXHAUSTED) {
                resourceExhaustions.increment();
                lastResourceExhaustionAt.set(now);
                resourceConstrained.set(true);
            }
        }
    }

    private static final class Aggregate {

        private int activeResources;
        private long successfulOpens;
        private long openFailures;
        private long operationFailures;
        private long resourceExhaustions;
        private boolean resourceConstrained;
        private Instant lastSuccessAt;
        private Instant lastResourceExhaustionAt;

        private void add(OwnerState state) {
            activeResources += state.activeResources.get();
            successfulOpens += state.successfulOpens.sum();
            openFailures += state.openFailures.sum();
            operationFailures += state.operationFailures.sum();
            resourceExhaustions += state.resourceExhaustions.sum();
            resourceConstrained |= state.resourceConstrained.get();
            lastSuccessAt = latest(lastSuccessAt, state.lastSuccessAt.get());
            lastResourceExhaustionAt = latest(
                    lastResourceExhaustionAt, state.lastResourceExhaustionAt.get());
        }

        private Snapshot snapshot(MetricKey key) {
            return new Snapshot(key.endpoint(), key.role(),
                    activeResources, activeResources, activeResources,
                    successfulOpens, openFailures, operationFailures,
                    resourceExhaustions, resourceConstrained,
                    lastSuccessAt, lastResourceExhaustionAt);
        }

        private static Instant latest(Instant first, Instant second) {
            if (first == null) {
                return second;
            }
            if (second == null || first.isAfter(second)) {
                return first;
            }
            return second;
        }
    }
}
