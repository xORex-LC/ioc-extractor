package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.transport.smb.SmbTransportTelemetry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Actuator projection of application-owned SMB resources and capacity failures. */
final class SmbTransportHealthIndicator implements HealthIndicator {

    private final Supplier<List<SmbTransportTelemetry.Snapshot>> snapshots;
    private final Map<String, Integer> plannedSessions;

    SmbTransportHealthIndicator(
            SmbTransportTelemetry telemetry,
            Map<String, Integer> plannedSessions) {
        this(telemetry::snapshot, plannedSessions);
    }

    SmbTransportHealthIndicator(
            Supplier<List<SmbTransportTelemetry.Snapshot>> snapshots,
            Map<String, Integer> plannedSessions) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.plannedSessions = Map.copyOf(Objects.requireNonNull(plannedSessions, "plannedSessions"));
    }

    @Override
    public Health health() {
        try {
            List<SmbTransportTelemetry.Snapshot> current = snapshots.get();
            boolean constrained = current.stream()
                    .anyMatch(SmbTransportTelemetry.Snapshot::resourceConstrained);
            Health.Builder builder = constrained
                    ? Health.status(new Status("DEGRADED"))
                    : Health.up();
            return builder.withDetail("endpoints", endpointDetails(current)).build();
        } catch (RuntimeException failure) {
            return Health.down(failure).build();
        }
    }

    private Map<String, Object> endpointDetails(List<SmbTransportTelemetry.Snapshot> current) {
        Set<String> endpoints = new TreeSet<>(plannedSessions.keySet());
        current.forEach(snapshot -> endpoints.add(snapshot.endpoint()));
        Map<String, Object> details = new LinkedHashMap<>();
        endpoints.forEach(endpoint -> details.put(endpoint, endpointDetail(endpoint, current)));
        return details;
    }

    private Map<String, Object> endpointDetail(
            String endpoint,
            List<SmbTransportTelemetry.Snapshot> current) {
        List<SmbTransportTelemetry.Snapshot> endpointSnapshots = current.stream()
                .filter(snapshot -> snapshot.endpoint().equals(endpoint))
                .toList();
        SmbTransportTelemetry.Snapshot pooled = snapshot(
                endpointSnapshots, endpoint, SmbTransportTelemetry.Role.POOLED_TRANSPORT);
        SmbTransportTelemetry.Snapshot watches = snapshot(
                endpointSnapshots, endpoint, SmbTransportTelemetry.Role.CHANGE_NOTIFY);
        boolean constrained = endpointSnapshots.stream()
                .anyMatch(SmbTransportTelemetry.Snapshot::resourceConstrained);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", constrained ? "DEGRADED" : "UP");
        detail.put("plannedSteadySessions", plannedSessions.getOrDefault(endpoint, 0));
        detail.put("ownedConnections", sum(endpointSnapshots, SmbTransportTelemetry.Snapshot::activeConnections));
        detail.put("ownedSessions", sum(endpointSnapshots, SmbTransportTelemetry.Snapshot::activeSessions));
        detail.put("activeTreeConnections",
                sum(endpointSnapshots, SmbTransportTelemetry.Snapshot::activeTreeConnections));
        detail.put("pooledSessions", pooled.activeSessions());
        detail.put("activeWatchSessions", watches.activeSessions());
        detail.put("successfulOpens", sumLong(endpointSnapshots,
                SmbTransportTelemetry.Snapshot::successfulOpens));
        detail.put("openFailures", sumLong(endpointSnapshots,
                SmbTransportTelemetry.Snapshot::openFailures));
        detail.put("operationFailures", sumLong(endpointSnapshots,
                SmbTransportTelemetry.Snapshot::operationFailures));
        detail.put("resourceExhaustions", sumLong(endpointSnapshots,
                SmbTransportTelemetry.Snapshot::resourceExhaustions));
        putLatest(detail, "lastSuccessAt", endpointSnapshots.stream()
                .map(SmbTransportTelemetry.Snapshot::lastSuccessAt).toList());
        putLatest(detail, "lastResourceExhaustionAt", endpointSnapshots.stream()
                .map(SmbTransportTelemetry.Snapshot::lastResourceExhaustionAt).toList());
        return detail;
    }

    private SmbTransportTelemetry.Snapshot snapshot(
            List<SmbTransportTelemetry.Snapshot> current,
            String endpoint,
            SmbTransportTelemetry.Role role) {
        return current.stream()
                .filter(snapshot -> snapshot.role() == role)
                .findFirst()
                .orElse(new SmbTransportTelemetry.Snapshot(
                        endpoint, role, 0, 0, 0,
                        0L, 0L, 0L, 0L, false, null, null));
    }

    private int sum(
            List<SmbTransportTelemetry.Snapshot> values,
            java.util.function.ToIntFunction<SmbTransportTelemetry.Snapshot> extractor) {
        return values.stream().mapToInt(extractor).sum();
    }

    private long sumLong(
            List<SmbTransportTelemetry.Snapshot> values,
            java.util.function.ToLongFunction<SmbTransportTelemetry.Snapshot> extractor) {
        return values.stream().mapToLong(extractor).sum();
    }

    private void putLatest(Map<String, Object> detail, String key, List<Instant> values) {
        values.stream().filter(Objects::nonNull).max(Instant::compareTo)
                .ifPresent(value -> detail.put(key, value.toString()));
    }
}
