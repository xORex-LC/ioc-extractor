package com.iocextractor.bootstrap;

import com.iocextractor.application.port.out.observation.ObservationRegistrationStatusReader;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Safe actuator visibility for unresolved ranks, including crashed oneshot work. */
final class ObservationRegistrationHealthIndicator implements HealthIndicator {

    private final ObservationRegistrationStatusReader reader;
    private final Clock clock;

    ObservationRegistrationHealthIndicator(ObservationRegistrationStatusReader reader, Clock clock) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Health health() {
        try {
            var status = reader.status();
            Health.Builder health = status.pendingOneshot() == 0 ? Health.up() : Health.down()
                    .withDetail("pending", status.pendingTotal())
                    .withDetail("pendingOneshot", status.pendingOneshot());
            status.oldestPendingOneshot().ifPresent(oldest -> health.withDetail(
                    "oldestPendingOneshotAgeSeconds",
                    Math.max(0, Duration.between(oldest, clock.instant()).toSeconds())));
            return health.build();
        } catch (RuntimeException failure) {
            return Health.down(failure).build();
        }
    }
}
