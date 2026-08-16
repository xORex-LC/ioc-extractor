package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.in.artifact.lifecycle.ConvergeArtifactProjectionsUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.ResumeLifecycleActivationUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleActivationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleControlStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Starts or resumes the explicit, one-way legacy activation workflow. */
public final class LifecycleActivationService implements ResumeLifecycleActivationUseCase {

    private final List<String> artifacts;
    private final LifecycleControlStore control;
    private final LifecycleActivationStore activationStore;
    private final ConvergeArtifactProjectionsUseCase projectionConvergence;
    private final LifecycleTimeSource timeSource;
    private final LifecycleActivationPolicy policy;
    private final int batchSize;

    public LifecycleActivationService(List<String> artifacts,
                                      LifecycleControlStore control,
                                      LifecycleActivationStore activationStore,
                                      ConvergeArtifactProjectionsUseCase projectionConvergence,
                                      LifecycleTimeSource timeSource,
                                      LifecycleActivationPolicy policy,
                                      int batchSize) {
        Objects.requireNonNull(artifacts, "artifacts");
        var unique = new LinkedHashSet<String>();
        for (String artifact : artifacts) {
            if (artifact == null || artifact.isBlank() || !unique.add(artifact)) {
                throw new IllegalArgumentException("Activation artifacts must be unique and non-blank");
            }
        }
        this.artifacts = List.copyOf(artifacts);
        this.control = Objects.requireNonNull(control, "control");
        this.activationStore = Objects.requireNonNull(activationStore, "activationStore");
        this.projectionConvergence = Objects.requireNonNull(projectionConvergence, "projectionConvergence");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Activation batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public void resume() {
        LifecycleControlState state = control.load();
        if (!policy.enabled()) {
            if (state.activationState() != LifecycleActivationState.DISABLED_COMPATIBLE) {
                throw mismatch("Validity cannot be disabled after lifecycle activation has started");
            }
            return;
        }
        if (state.activationState() == LifecycleActivationState.ACTIVE) {
            requireFingerprint(state);
            return;
        }
        if (state.activationState() == LifecycleActivationState.DISABLED_COMPATIBLE) {
            if (activationStore.hasLegacyRecords()
                    && policy.existingRecords() != ExistingRecordsActivationPolicy.EXPIRE) {
                throw mismatch("Legacy canonical rows require ioc.lifecycle.validity.existing-records=expire");
            }
            LifecycleControlState activating = state.beginActivation(policy.policyFingerprint());
            if (!control.compareAndSet(state, activating)) {
                state = control.load();
            } else {
                state = activating;
            }
        }
        if (state.activationState() != LifecycleActivationState.ACTIVATING) {
            requireFingerprint(state);
            return;
        }
        requireFingerprint(state);
        EffectiveTime activationAsOf = timeSource.now();
        for (String artifact : artifacts) {
            LifecycleActivationBatchResult batch;
            do {
                batch = activationStore.expireLegacyBatch(artifact, activationAsOf, batchSize);
            } while (batch.moreLegacyRows());
        }
        projectionConvergence.convergePending();
        LifecycleControlState latest = control.load();
        requireFingerprint(latest);
        if (latest.activationState() == LifecycleActivationState.ACTIVE) {
            return;
        }
        if (!control.compareAndSet(latest, latest.completeActivation(timeSource.now()))) {
            LifecycleControlState raced = control.load();
            if (raced.activationState() != LifecycleActivationState.ACTIVE) {
                throw new IllegalStateException("Lifecycle activation completion lost its durable state");
            }
            requireFingerprint(raced);
        }
    }

    private void requireFingerprint(LifecycleControlState state) {
        if (state.activationState() == LifecycleActivationState.DISABLED_COMPATIBLE) {
            throw mismatch("Fixed validity did not start lifecycle activation");
        }
        if (!state.policyFingerprint().orElseThrow().equals(policy.policyFingerprint())) {
            throw mismatch("Persisted lifecycle policy is incompatible with the configured policy");
        }
    }

    private LifecyclePolicyMismatchException mismatch(String message) {
        return new LifecyclePolicyMismatchException(message);
    }
}
