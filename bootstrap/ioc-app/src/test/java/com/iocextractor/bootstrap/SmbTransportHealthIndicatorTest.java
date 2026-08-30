package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.transport.smb.SmbTransportTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmbTransportHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void reportsOwnedResourcesAndOutstandingCapacityFailureAsDegraded() {
        var indicator = new SmbTransportHealthIndicator(
                () -> List.of(
                        snapshot(SmbTransportTelemetry.Role.POOLED_TRANSPORT, 1, 0, false),
                        snapshot(SmbTransportTelemetry.Role.CHANGE_NOTIFY, 2, 1, true)),
                Map.of("primary", 3));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
        assertThat(endpointDetails(health.getDetails()))
                .containsEntry("status", "DEGRADED")
                .containsEntry("plannedSteadySessions", 3)
                .containsEntry("ownedConnections", 3)
                .containsEntry("ownedSessions", 3)
                .containsEntry("activeTreeConnections", 3)
                .containsEntry("pooledSessions", 1)
                .containsEntry("activeWatchSessions", 2)
                .containsEntry("resourceExhaustions", 1L)
                .containsEntry("lastResourceExhaustionAt", NOW.toString());
    }

    @Test
    void returnsUpAfterTheCapacitySignalRecovers() {
        var indicator = new SmbTransportHealthIndicator(
                () -> List.of(snapshot(
                        SmbTransportTelemetry.Role.CHANGE_NOTIFY, 1, 1, false)),
                Map.of("primary", 2));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    private SmbTransportTelemetry.Snapshot snapshot(
            SmbTransportTelemetry.Role role,
            int active,
            long resourceExhaustions,
            boolean constrained) {
        return new SmbTransportTelemetry.Snapshot(
                "primary", role, active, active, active,
                1L, resourceExhaustions, 0L, resourceExhaustions,
                constrained, NOW, resourceExhaustions == 0 ? null : NOW);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> endpointDetails(Map<String, Object> healthDetails) {
        Map<String, Map<String, Object>> endpoints =
                (Map<String, Map<String, Object>>) healthDetails.get("endpoints");
        return endpoints.get("primary");
    }
}
