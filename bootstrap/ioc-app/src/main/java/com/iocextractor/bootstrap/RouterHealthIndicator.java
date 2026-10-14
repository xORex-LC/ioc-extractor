package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Daemon readiness view for an activated routing runtime. */
public final class RouterHealthIndicator implements HealthIndicator {
    private final CamelRouteRuntime runtime;

    public RouterHealthIndicator(CamelRouteRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    @Override public Health health() {
        return runtime.isReady() ? Health.up().build() : Health.down().build();
    }
}
