package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.ObservationRegistrationStatus;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationRegistrationOperationsTest {

    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void unresolvedOneshotIsVisibleAndDegradesHealth() {
        var indicator = new ObservationRegistrationHealthIndicator(
                () -> new ObservationRegistrationStatus(
                        3, 1, Optional.of(NOW.minusSeconds(90))), CLOCK);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("pending", 3L)
                .containsEntry("pendingOneshot", 1L)
                .containsEntry("oldestPendingOneshotAgeSeconds", 90L);
    }

    @Test
    void retentionUsesReceiptHorizonAndNeverPurgesUnresolvedOneshotDirectly() {
        var registrations = new RecordingRegistrationStore();
        var scheduler = new ObservationRegistrationRetentionScheduler(
                registrations, null, null, CLOCK, Duration.ofDays(30), Duration.ofHours(1));

        scheduler.runOnce();

        assertThat(registrations.cutoff).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(registrations.limit).isEqualTo(1_000);
    }

    private static final class RecordingRegistrationStore implements ObservationRegistrationStore {
        private Instant cutoff;
        private int limit;

        @Override
        public RegisteredObservation registerNew(ObservationId observationId, ObservationOrigin origin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegisteredObservation resume(ObservationId observationId, String expectedNamespace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markTerminal(ObservationId observationId, String expectedNamespace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean purgeTerminal(RegisteredObservation registration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeTerminalOneshotBefore(Instant cutoff, int limit) {
            this.cutoff = cutoff;
            this.limit = limit;
            return 0;
        }
    }
}
