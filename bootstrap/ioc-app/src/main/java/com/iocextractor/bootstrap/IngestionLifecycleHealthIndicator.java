package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.IngestionLifecycleState;
import com.iocextractor.platform.concurrent.KeyedExecutionGuard;
import com.iocextractor.platform.concurrent.KeyedExecutionGuardSnapshot;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.Lifecycle;

import java.util.Objects;

/** Actuator view of the recovery-before-intake barrier and source-key contention. */
public final class IngestionLifecycleHealthIndicator implements HealthIndicator {

    private final IngestionLifecycleState lifecycleState;
    private final Lifecycle intakeFlow;
    private final KeyedExecutionGuard executionGuard;

    /** Creates an ingestion lifecycle contributor without exposing source-key values. */
    public IngestionLifecycleHealthIndicator(IngestionLifecycleState lifecycleState,
                                             Lifecycle intakeFlow,
                                             KeyedExecutionGuard executionGuard) {
        this.lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        this.intakeFlow = Objects.requireNonNull(intakeFlow, "intakeFlow");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
    }

    @Override
    public Health health() {
        IngestionLifecycleState.Snapshot lifecycle = lifecycleState.snapshot();
        KeyedExecutionGuardSnapshot guard = executionGuard.snapshot();
        boolean intakeRunning = intakeFlow.isRunning();
        boolean healthy = lifecycle.phase() == IngestionLifecycleState.Phase.RUNNING && intakeRunning;

        Health.Builder builder = healthy ? Health.up() : Health.down();
        builder.withDetail("phase", lifecycle.phase().name())
                .withDetail("intakeRunning", intakeRunning)
                .withDetail("recoveredRuns", lifecycle.recoveredRuns())
                .withDetail("recoveredSources", lifecycle.recoveredSources())
                .withDetail("activeSourceKeys", guard.activeKeys())
                .withDetail("executing", guard.executing())
                .withDetail("waiting", guard.waiting());
        if (lifecycle.recoveryStartedAt() != null) {
            builder.withDetail("recoveryStartedAt", lifecycle.recoveryStartedAt().toString());
        }
        if (lifecycle.recoveryCompletedAt() != null) {
            builder.withDetail("recoveryCompletedAt", lifecycle.recoveryCompletedAt().toString());
        }
        if (lifecycle.failure() != null) {
            builder.withDetail("failure", lifecycle.failure());
        }
        return builder.build();
    }
}
