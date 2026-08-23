package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.LifecycleArtifactStatistics;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockStatus;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconcileCycleState;
import com.iocextractor.application.artifact.lifecycle.LifecycleStatusSnapshot;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleStatusReader;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Read-only aggregate lifecycle readiness and convergence view. */
public final class LifecycleHealthIndicator implements HealthIndicator {

    private final LifecycleStatusReader statusReader;
    private final CanonicalDataAdmissionState admission;
    private final Duration dueTolerance;

    public LifecycleHealthIndicator(LifecycleStatusReader statusReader,
                                    CanonicalDataAdmissionState admission,
                                    Duration dueTolerance) {
        this.statusReader = Objects.requireNonNull(statusReader, "statusReader");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.dueTolerance = requirePositive(dueTolerance);
    }

    @Override
    public Health health() {
        CanonicalDataAdmissionState.Snapshot admissionSnapshot = admission.snapshot();
        try {
            LifecycleStatusSnapshot status = statusReader.read();
            Health.Builder builder = healthBuilder(admissionSnapshot, status);
            builder.withDetail("admission", admissionSnapshot.phase().name())
                    .withDetail("activation", status.control().activationState().name())
                    .withDetail("clock", status.clock().status().name())
                    .withDetail("clockBackwardSkewMs", status.clock().backwardSkew().toMillis())
                    .withDetail("clockClampAgeMs", status.clock().clampAge().toMillis())
                    .withDetail("dueRecords", status.dueRecords())
                    .withDetail("historyRecords", status.historyRecords())
                    .withDetail("pendingProjections", status.pendingProjections())
                    .withDetail("dueBacklogAgeMs", status.dueBacklogAge().toMillis())
                    .withDetail("latestCycle", status.latestCycleState().name())
                    .withDetail("latestCycleExpired", status.latestCycleExpired())
                    .withDetail("artifacts", artifactDetails(status));
            status.nearestDeadline().ifPresent(value ->
                    builder.withDetail("nearestDeadline", value.toString()));
            status.latestFailureCode().ifPresent(value ->
                    builder.withDetail("lastFailureCode", value));
            if (admissionSnapshot.failure() != null) {
                builder.withDetail("admissionFailure", admissionSnapshot.failure());
            }
            return builder.build();
        } catch (RuntimeException failure) {
            return Health.down()
                    .withDetail("admission", admissionSnapshot.phase().name())
                    .withDetail("failure", failure.getClass().getSimpleName())
                    .build();
        }
    }

    private Health.Builder healthBuilder(CanonicalDataAdmissionState.Snapshot admissionSnapshot,
                                         LifecycleStatusSnapshot status) {
        if (admissionSnapshot.phase() == CanonicalDataAdmissionState.Phase.FAILED
                || status.clock().status() == LifecycleClockStatus.UNSAFE) {
            return Health.down();
        }
        boolean admissionPending = admissionSnapshot.phase() != CanonicalDataAdmissionState.Phase.ADMITTED;
        boolean convergenceLag = status.clock().status() == LifecycleClockStatus.CLAMPED
                || status.pendingProjections() > 0
                || status.dueBacklogAge().compareTo(dueTolerance) > 0
                || status.latestCycleState() == LifecycleReconcileCycleState.FAILED
                || status.latestCycleState() == LifecycleReconcileCycleState.STARTED;
        if (admissionPending || convergenceLag) {
            return Health.status("DEGRADED");
        }
        return Health.up();
    }

    private Map<String, Object> artifactDetails(LifecycleStatusSnapshot status) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (LifecycleArtifactStatistics artifact : status.artifacts()) {
            details.put(artifact.artifactName(), Map.of(
                    "stored", artifact.stored(),
                    "due", artifact.due(),
                    "history", artifact.history()));
        }
        return details;
    }

    private Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "dueTolerance");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("dueTolerance must be positive");
        }
        return value;
    }
}
