package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.sync.RemoteErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;

import static com.iocextractor.adapter.out.transport.smb.SmbTransportTelemetry.Role.CHANGE_NOTIFY;
import static com.iocextractor.adapter.out.transport.smb.SmbTransportTelemetry.Role.POOLED_TRANSPORT;
import static org.assertj.core.api.Assertions.assertThat;

class SmbTransportTelemetryTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void tracksEstablishedResourcesAndIdempotentClose() {
        SmbTransportTelemetry telemetry = telemetry();
        SmbTransportTelemetry.Lease lease = telemetry.sessionOpened(
                "primary", POOLED_TRANSPORT, "pool");

        assertThat(telemetry.snapshot("primary", POOLED_TRANSPORT))
                .extracting(
                        SmbTransportTelemetry.Snapshot::activeConnections,
                        SmbTransportTelemetry.Snapshot::activeSessions,
                        SmbTransportTelemetry.Snapshot::activeTreeConnections,
                        SmbTransportTelemetry.Snapshot::successfulOpens)
                .containsExactly(1, 1, 1, 1L);

        lease.close();
        lease.close();

        assertThat(telemetry.snapshot("primary", POOLED_TRANSPORT).activeSessions()).isZero();
    }

    @Test
    void keepsOneOwnersCapacityFailureUntilThatOwnerRecovers() {
        SmbTransportTelemetry telemetry = telemetry();
        telemetry.recordOpenFailure("primary", CHANGE_NOTIFY, "source-a",
                RemoteErrorKind.RESOURCE_EXHAUSTED);

        try (SmbTransportTelemetry.Lease ignored = telemetry.sessionOpened(
                "primary", CHANGE_NOTIFY, "source-b")) {
            assertThat(telemetry.snapshot("primary", CHANGE_NOTIFY).resourceConstrained()).isTrue();
        }

        try (SmbTransportTelemetry.Lease ignored = telemetry.sessionOpened(
                "primary", CHANGE_NOTIFY, "source-a")) {
            assertThat(telemetry.snapshot("primary", CHANGE_NOTIFY).resourceConstrained()).isFalse();
        }
    }

    @Test
    void concurrentCloseCannotDriveActiveResourcesNegative() throws Exception {
        SmbTransportTelemetry telemetry = telemetry();
        SmbTransportTelemetry.Lease lease = telemetry.sessionOpened(
                "primary", POOLED_TRANSPORT, "pool");

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 100; index++) {
                executor.submit(lease::close);
            }
        }

        assertThat(telemetry.snapshot("primary", POOLED_TRANSPORT).activeSessions()).isZero();
    }

    private SmbTransportTelemetry telemetry() {
        return new SmbTransportTelemetry(Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
