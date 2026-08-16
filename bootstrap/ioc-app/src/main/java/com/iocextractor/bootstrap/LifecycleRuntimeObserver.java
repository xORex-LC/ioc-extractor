package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionConvergenceResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockUnsafeException;
import com.iocextractor.application.artifact.lifecycle.LifecycleHistoryRetentionResult;
import com.iocextractor.application.artifact.lifecycle.LifecyclePolicyMismatchException;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconciliationResult;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.LifecycleDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Aggregate diagnostics and ECS events for lifecycle operations. */
public final class LifecycleRuntimeObserver {

    private static final Logger log = LoggerFactory.getLogger(LifecycleRuntimeObserver.class);
    private final DiagnosticSink diagnostics;
    private final DiagnosticFactory diagnosticFactory;

    public LifecycleRuntimeObserver(DiagnosticSink diagnostics, DiagnosticFactory diagnosticFactory) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    public void admissionCompleted(LifecycleAdmissionResult result) {
        LogEvents.info(log)
                .action(EventAction.LIFECYCLE_ADMISSION)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_LIFECYCLE_STATE, result.activationState().name())
                .field(LogField.IOC_LIFECYCLE_EXPIRED, result.expired())
                .field(LogField.IOC_LIFECYCLE_PROJECTIONS, result.projectionsConverged())
                .message("canonical lifecycle admission completed")
                .log();
    }

    public void admissionFailed(RuntimeException failure) {
        LifecycleDiagnosticCodes code = switch (failure) {
            case LifecycleClockUnsafeException ignored -> LifecycleDiagnosticCodes.CLOCK_UNSAFE;
            case LifecyclePolicyMismatchException ignored -> LifecycleDiagnosticCodes.POLICY_MISMATCH;
            default -> LifecycleDiagnosticCodes.ADMISSION_FAILED;
        };
        emit(code, failure);
        LogEvents.error(log)
                .action(failure instanceof LifecycleClockUnsafeException
                        ? EventAction.LIFECYCLE_CLOCK : EventAction.LIFECYCLE_ADMISSION)
                .outcome(EventOutcome.FAILURE)
                .message("canonical lifecycle admission failed")
                .log(failure);
    }

    public void reconciliationCompleted(LifecycleReconciliationResult result) {
        LogEvents.info(log)
                .action(EventAction.LIFECYCLE_RECONCILE)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_LIFECYCLE_CYCLE_ID, result.cycleId().value())
                .field(LogField.IOC_LIFECYCLE_EXPIRED, result.expired())
                .message("canonical lifecycle reconciliation completed")
                .log();
    }

    public void reconciliationFailed(RuntimeException failure) {
        emit(LifecycleDiagnosticCodes.RECONCILIATION_FAILED, failure);
        LogEvents.error(log)
                .action(EventAction.LIFECYCLE_RECONCILE)
                .outcome(EventOutcome.FAILURE)
                .message("canonical lifecycle reconciliation failed")
                .log(failure);
    }

    public void deadlineLookupFailed(RuntimeException failure) {
        emit(LifecycleDiagnosticCodes.RECONCILIATION_FAILED, failure);
        LogEvents.error(log)
                .action(EventAction.LIFECYCLE_RECONCILE)
                .outcome(EventOutcome.FAILURE)
                .message("canonical lifecycle deadline lookup failed")
                .log(failure);
    }

    public void projectionCompleted(ArtifactProjectionConvergenceResult result) {
        LogEvents.info(log)
                .action(EventAction.LIFECYCLE_PROJECTION)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_LIFECYCLE_PROJECTIONS, result.projected())
                .message("canonical mutable projection convergence completed")
                .log();
    }

    public void projectionFailed(RuntimeException failure) {
        emit(LifecycleDiagnosticCodes.PROJECTION_FAILED, failure);
        LogEvents.error(log)
                .action(EventAction.LIFECYCLE_PROJECTION)
                .outcome(EventOutcome.FAILURE)
                .message("canonical mutable projection convergence failed")
                .log(failure);
    }

    public void retentionCompleted(LifecycleHistoryRetentionResult result) {
        if (result.purged() == 0) {
            return;
        }
        LogEvents.info(log)
                .action(EventAction.LIFECYCLE_RETENTION)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_ROWS, result.purged())
                .message("canonical lifecycle history retention completed")
                .log();
    }

    public void retentionFailed(RuntimeException failure) {
        emit(LifecycleDiagnosticCodes.HISTORY_RETENTION_FAILED, failure);
        LogEvents.error(log)
                .action(EventAction.LIFECYCLE_RETENTION)
                .outcome(EventOutcome.FAILURE)
                .message("canonical lifecycle history retention failed")
                .log(failure);
    }

    private void emit(LifecycleDiagnosticCodes code, RuntimeException failure) {
        try {
            diagnostics.emit(diagnosticFactory.create(code)
                    .with("reason", failure.getClass().getSimpleName())
                    .cause(failure)
                    .build());
        } catch (RuntimeException ignored) {
            // Operational observation never becomes lifecycle correctness authority.
        }
    }
}
