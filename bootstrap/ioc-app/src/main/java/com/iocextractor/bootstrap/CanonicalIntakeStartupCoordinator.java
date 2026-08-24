package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.IngestionLifecycleState;
import com.iocextractor.adapter.in.ingest.IngestionStartupObserver;
import com.iocextractor.application.artifact.IngestRunRecoveryService;
import com.iocextractor.application.port.in.artifact.lifecycle.PrepareLifecycleAdmissionUseCase;
import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.Lifecycle;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** One recovery-before-intake barrier shared by ordinary and dataframe ingestion. */
final class CanonicalIntakeStartupCoordinator implements ApplicationRunner, Ordered {

    private final IngestRunRecoveryService runRecovery;
    private final RecoverIngestionUseCase sourceRecovery;
    private final PrepareLifecycleAdmissionUseCase lifecycleAdmission;
    private final Lifecycle intakeFlow;
    private final DataframeImportRuntimeLifecycle importRuntime;
    private final IngestionLifecycleState lifecycleState;
    private final IngestionStartupObserver observer;
    private final Clock clock;

    CanonicalIntakeStartupCoordinator(
            IngestRunRecoveryService runRecovery,
            RecoverIngestionUseCase sourceRecovery,
            PrepareLifecycleAdmissionUseCase lifecycleAdmission,
            Lifecycle intakeFlow,
            DataframeImportRuntimeLifecycle importRuntime,
            IngestionLifecycleState lifecycleState,
            IngestionStartupObserver observer,
            Clock clock) {
        this.runRecovery = Objects.requireNonNull(runRecovery, "runRecovery");
        this.sourceRecovery = Objects.requireNonNull(sourceRecovery, "sourceRecovery");
        this.lifecycleAdmission = Objects.requireNonNull(lifecycleAdmission, "lifecycleAdmission");
        this.intakeFlow = Objects.requireNonNull(intakeFlow, "intakeFlow");
        this.importRuntime = importRuntime;
        this.lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant startedAt = clock.instant();
        lifecycleState.recoveryStarted(startedAt);
        observe(() -> observer.recoveryStarted(startedAt));
        try {
            requireClosedIntake();
            int recoveredRuns = runRecovery.recover();
            int recoveredSources = sourceRecovery.recoverIncomplete().size();
            lifecycleAdmission.prepare();
            if (importRuntime != null) {
                importRuntime.recoverBeforeIntake();
                importRuntime.start();
            }
            intakeFlow.start();
            if (!intakeFlow.isRunning()) {
                throw new IllegalStateException("Ingestion intake did not start after successful recovery");
            }
            Instant completedAt = clock.instant();
            lifecycleState.running(completedAt, recoveredRuns, recoveredSources);
            observe(() -> observer.recoveryCompleted(
                    startedAt, completedAt, recoveredRuns, recoveredSources));
        } catch (RuntimeException failure) {
            stopAfterFailure(failure);
            Instant failedAt = clock.instant();
            lifecycleState.failed(failedAt, failure);
            observeFailure(() -> observer.recoveryFailed(startedAt, failedAt, failure), failure);
            throw failure;
        }
    }

    private void requireClosedIntake() {
        if (intakeFlow.isRunning()) {
            throw new IllegalStateException("Ingestion intake must remain stopped until startup recovery");
        }
    }

    private void stopAfterFailure(RuntimeException failure) {
        try {
            intakeFlow.stop();
        } catch (RuntimeException stopFailure) {
            failure.addSuppressed(stopFailure);
        }
        if (importRuntime != null) {
            try {
                importRuntime.close();
            } catch (RuntimeException stopFailure) {
                failure.addSuppressed(stopFailure);
            }
        }
    }

    private void observe(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Operational observation must not change startup correctness.
        }
    }

    private void observeFailure(Runnable callback, RuntimeException primaryFailure) {
        try {
            callback.run();
        } catch (RuntimeException observationFailure) {
            primaryFailure.addSuppressed(observationFailure);
        }
    }
}
