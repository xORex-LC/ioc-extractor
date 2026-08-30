package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.transport.smb.SmbTransportTelemetry;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.Objects;

/** Low-cardinality Micrometer projection of application-owned SMB resources. */
final class SmbTransportMetrics {

    SmbTransportMetrics(
            MeterRegistry registry,
            SmbTransportTelemetry telemetry,
            Map<String, Integer> plannedSessions) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(plannedSessions, "plannedSessions");
        plannedSessions.keySet().stream().sorted().forEach(endpoint -> {
            for (SmbTransportTelemetry.Role role : SmbTransportTelemetry.Role.values()) {
                registerRole(registry, telemetry, endpoint, role);
            }
        });
    }

    private void registerRole(
            MeterRegistry registry,
            SmbTransportTelemetry telemetry,
            String endpoint,
            SmbTransportTelemetry.Role role) {
        registerGauge(registry, telemetry, endpoint, role,
                "ioc.smb.connections.active", SmbTransportTelemetry.Snapshot::activeConnections);
        registerGauge(registry, telemetry, endpoint, role,
                "ioc.smb.sessions.active", SmbTransportTelemetry.Snapshot::activeSessions);
        registerGauge(registry, telemetry, endpoint, role,
                "ioc.smb.tree.connections.active", SmbTransportTelemetry.Snapshot::activeTreeConnections);
        registerCounter(registry, telemetry, endpoint, role,
                "ioc.smb.session.opens", "success", SmbTransportTelemetry.Snapshot::successfulOpens);
        registerCounter(registry, telemetry, endpoint, role,
                "ioc.smb.session.opens", "failure", SmbTransportTelemetry.Snapshot::openFailures);
        registerCounter(registry, telemetry, endpoint, role,
                "ioc.smb.operation.failures", null, SmbTransportTelemetry.Snapshot::operationFailures);
        registerCounter(registry, telemetry, endpoint, role,
                "ioc.smb.resource.exhaustions", null, SmbTransportTelemetry.Snapshot::resourceExhaustions);
    }

    private void registerGauge(
            MeterRegistry registry,
            SmbTransportTelemetry telemetry,
            String endpoint,
            SmbTransportTelemetry.Role role,
            String name,
            java.util.function.ToDoubleFunction<SmbTransportTelemetry.Snapshot> value) {
        Gauge.builder(name, telemetry,
                        source -> value.applyAsDouble(source.snapshot(endpoint, role)))
                .tag("endpoint", endpoint)
                .tag("role", role.tagValue())
                .register(registry);
    }

    private void registerCounter(
            MeterRegistry registry,
            SmbTransportTelemetry telemetry,
            String endpoint,
            SmbTransportTelemetry.Role role,
            String name,
            String outcome,
            java.util.function.ToDoubleFunction<SmbTransportTelemetry.Snapshot> value) {
        FunctionCounter.Builder<SmbTransportTelemetry> builder = FunctionCounter.builder(
                        name, telemetry,
                        source -> value.applyAsDouble(source.snapshot(endpoint, role)))
                .tag("endpoint", endpoint)
                .tag("role", role.tagValue());
        if (outcome != null) {
            builder.tag("outcome", outcome);
        }
        builder.register(registry);
    }
}
