package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.in.artifact.lifecycle.ConvergeArtifactProjectionsUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.PrepareLifecycleAdmissionUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.ReconcileExpiredRecordsUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.ResumeLifecycleActivationUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleControlStore;

import java.util.Objects;

/** Common idempotent admission sequence shared by stateful driving adapters. */
public final class LifecycleAdmissionService implements PrepareLifecycleAdmissionUseCase {

    private final LifecycleControlStore control;
    private final LifecycleTimeSource timeSource;
    private final ResumeLifecycleActivationUseCase activationRecovery;
    private final ReconcileExpiredRecordsUseCase reconciliation;
    private final ConvergeArtifactProjectionsUseCase projectionConvergence;
    private final CanonicalDataAdmissionState admissionState;

    public LifecycleAdmissionService(LifecycleControlStore control,
                                     LifecycleTimeSource timeSource,
                                     ResumeLifecycleActivationUseCase activationRecovery,
                                     ReconcileExpiredRecordsUseCase reconciliation,
                                     ConvergeArtifactProjectionsUseCase projectionConvergence,
                                     CanonicalDataAdmissionState admissionState) {
        this.control = Objects.requireNonNull(control, "control");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.activationRecovery = Objects.requireNonNull(activationRecovery, "activationRecovery");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.projectionConvergence = Objects.requireNonNull(projectionConvergence, "projectionConvergence");
        this.admissionState = Objects.requireNonNull(admissionState, "admissionState");
    }

    @Override
    public synchronized LifecycleAdmissionResult prepare() {
        CanonicalDataAdmissionState.Snapshot current = admissionState.snapshot();
        if (current.phase() == CanonicalDataAdmissionState.Phase.ADMITTED) {
            return current.result();
        }
        if (current.phase() == CanonicalDataAdmissionState.Phase.PREPARING) {
            throw new IllegalStateException("Canonical lifecycle admission is already running");
        }

        admissionState.preparing();
        try {
            EffectiveTime effectiveTime = timeSource.now();
            LifecycleControlState lifecycle = control.load();
            if (lifecycle.activationState() == LifecycleActivationState.ACTIVATING) {
                activationRecovery.resume();
                lifecycle = control.load();
                if (lifecycle.activationState() != LifecycleActivationState.ACTIVE) {
                    throw new IllegalStateException("Lifecycle activation recovery did not reach ACTIVE");
                }
            }

            int expired = 0;
            int projected = 0;
            if (lifecycle.activationState() == LifecycleActivationState.ACTIVE) {
                expired = reconciliation.reconcile().expired();
                projected = projectionConvergence.convergePending().projected();
            }
            LifecycleAdmissionResult result = new LifecycleAdmissionResult(
                    lifecycle.activationState(), effectiveTime, expired, projected);
            admissionState.admitted(result);
            return result;
        } catch (RuntimeException failure) {
            admissionState.failed(failure);
            throw failure;
        }
    }
}
