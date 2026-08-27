package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryStatus;
import com.iocextractor.application.port.in.dataframeimport.QueryDataframeImportStatusUseCase;
import com.iocextractor.application.dataframeimport.DataframeImportSourceReadinessCoordinator;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessStatus;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

/** Value-free actuator view backed by indexed aggregate ledger queries. */
final class DataframeImportHealthIndicator implements HealthIndicator {

    private final QueryDataframeImportStatusUseCase statusUseCase;
    private final DataframeImportRuntimeState runtimeState;
    private final KeyedSerialExecutor lanes;
    private final DataframeImportSourceReadinessCoordinator readiness;

    DataframeImportHealthIndicator(QueryDataframeImportStatusUseCase statusUseCase,
                                   DataframeImportRuntimeState runtimeState,
                                   KeyedSerialExecutor lanes) {
        this.statusUseCase = Objects.requireNonNull(statusUseCase, "statusUseCase");
        this.runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
        this.lanes = Objects.requireNonNull(lanes, "lanes");
        this.readiness = new DataframeImportSourceReadinessCoordinator(
                com.iocextractor.application.dataframeimport.model.ImportSourceReadiness::ready);
    }

    DataframeImportHealthIndicator(QueryDataframeImportStatusUseCase statusUseCase,
                                   DataframeImportRuntimeState runtimeState,
                                   KeyedSerialExecutor lanes,
                                   DataframeImportSourceReadinessCoordinator readiness) {
        this.statusUseCase = Objects.requireNonNull(statusUseCase, "statusUseCase");
        this.runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
        this.lanes = Objects.requireNonNull(lanes, "lanes");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @Override
    public Health health() {
        DataframeImportRuntimeState.Snapshot runtime = runtimeState.snapshot();
        ImportDeliveryStatus status = statusUseCase.status();
        var laneSnapshot = lanes.snapshot();
        int queued = laneSnapshot.keys().stream().mapToInt(value -> value.queuedDepth()).sum();
        long running = laneSnapshot.keys().stream().filter(value -> value.running()).count();

        boolean retryingHead = status.headRetryCount() > 0 || status.headRetryDelay().isPresent();
        var sourceStates = readiness.snapshot();
        long incompatibleSources = sourceStates.stream()
                .filter(value -> value.status() == ImportSourceReadinessStatus.INCOMPATIBLE).count();
        long transientSources = sourceStates.stream()
                .filter(value -> value.status() == ImportSourceReadinessStatus.TRANSIENTLY_UNAVAILABLE).count();
        Health.Builder builder;
        if (incompatibleSources > 0) {
            builder = Health.down();
        } else if (transientSources > 0) {
            builder = Health.status("DEGRADED");
        } else {
            builder = switch (runtime.phase()) {
                case RUNNING -> retryingHead ? Health.status("DEGRADED") : Health.up();
                case DEGRADED, PENDING, RECOVERING, STOPPED -> Health.status("DEGRADED");
                case FAILED -> Health.down();
            };
        }
        builder.withDetail("phase", runtime.phase().name())
                .withDetail("recoveryComplete", status.recoveryComplete())
                .withDetail("nonterminal", status.stateCounts().values().stream()
                        .mapToLong(Long::longValue).sum())
                .withDetail("queuedWork", queued)
                .withDetail("runningLanes", running);
        if (!sourceStates.isEmpty()) {
            builder.withDetail("readySources", sourceStates.stream()
                            .filter(value -> value.status() == ImportSourceReadinessStatus.READY).count())
                    .withDetail("transientSources", transientSources)
                    .withDetail("incompatibleSources", incompatibleSources);
        }
        status.headSequence().ifPresent(sequence ->
                builder.withDetail("headSequence", sequence.value()));
        status.headState().ifPresent(state ->
                builder.withDetail("headState", state.name()));
        status.headAge().ifPresent(age ->
                builder.withDetail("headAgeSeconds", age.toSeconds()));
        if (status.headRetryCount() > 0) {
            builder.withDetail("headRetryCount", status.headRetryCount());
        }
        status.headRetryDelay().ifPresent(delay ->
                builder.withDetail("headRetryDelaySeconds", delay.toSeconds()));
        String readinessCode = sourceStates.stream()
                .filter(value -> value.status() != ImportSourceReadinessStatus.READY)
                .map(value -> value.diagnosticCode()).sorted().findFirst().orElse(null);
        String safeCode = readinessCode != null ? readinessCode
                : runtime.code() != null ? runtime.code() : status.headCode().orElse(null);
        if (safeCode != null) {
            builder.withDetail("code", safeCode);
        }
        return builder.build();
    }
}
