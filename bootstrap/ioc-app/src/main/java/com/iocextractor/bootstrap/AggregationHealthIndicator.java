package com.iocextractor.bootstrap;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Reports the latest scheduled aggregation state.
 */
public final class AggregationHealthIndicator implements HealthIndicator {

    private final AggregationState state;

    public AggregationHealthIndicator(AggregationState state) {
        this.state = state;
    }

    @Override
    public Health health() {
        AggregationState.Snapshot snapshot = state.snapshot();
        Health.Builder builder = snapshot.successful() ? Health.up() : Health.down();
        snapshot.updatedAtOptional().ifPresent(updatedAt -> builder.withDetail("updatedAt", updatedAt));
        if (snapshot.result() != null) {
            builder.withDetail("sourcesProcessed", snapshot.result().sourcesProcessed());
            builder.withDetail("partitionsRead", snapshot.result().partitionsRead());
            builder.withDetail("newStableIds", snapshot.result().newStableIds());
        }
        if (snapshot.failureMessage() != null) {
            builder.withDetail("error", snapshot.failureMessage());
        }
        return builder.build();
    }
}
