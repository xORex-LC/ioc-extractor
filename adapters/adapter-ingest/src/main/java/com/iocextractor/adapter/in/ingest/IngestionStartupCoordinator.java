package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;
import com.iocextractor.application.port.in.artifact.lifecycle.PrepareLifecycleAdmissionUseCase;
import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.Lifecycle;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Owns the daemon startup barrier between durable recovery and file intake.
 * The inbound flow is configured not to auto-start and is opened only after
 * both recovery steps complete successfully.
 */
public final class IngestionStartupCoordinator implements ApplicationRunner, Ordered {

    private final IntSupplier recoverIngestRuns;
    private final RecoverIngestionUseCase recoverSources;
    private final Lifecycle intakeFlow;
    private final IngestionLifecycleState lifecycleState;
    private final IngestionStartupObserver observer;
    private final PrepareLifecycleAdmissionUseCase lifecycleAdmission;
    private final Clock clock;

    public IngestionStartupCoordinator(IntSupplier recoverIngestRuns,
                                       RecoverIngestionUseCase recoverSources,
                                       Lifecycle intakeFlow,
                                       IngestionLifecycleState lifecycleState,
                                       IngestionStartupObserver observer,
                                       Clock clock) {
        this(recoverIngestRuns, recoverSources, intakeFlow, lifecycleState, observer,
                () -> new LifecycleAdmissionResult(
                        LifecycleActivationState.DISABLED_COMPATIBLE,
                        EffectiveTime.at(Instant.EPOCH), 0, 0),
                clock);
    }

    public IngestionStartupCoordinator(IntSupplier recoverIngestRuns,
                                       RecoverIngestionUseCase recoverSources,
                                       Lifecycle intakeFlow,
                                       IngestionLifecycleState lifecycleState,
                                       IngestionStartupObserver observer,
                                       PrepareLifecycleAdmissionUseCase lifecycleAdmission,
                                       Clock clock) {
        this.recoverIngestRuns = Objects.requireNonNull(recoverIngestRuns, "recoverIngestRuns");
        this.recoverSources = Objects.requireNonNull(recoverSources, "recoverSources");
        this.intakeFlow = Objects.requireNonNull(intakeFlow, "intakeFlow");
        this.lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.lifecycleAdmission = Objects.requireNonNull(lifecycleAdmission, "lifecycleAdmission");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Runs the recovery barrier before any other ordered application runner. */
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
            if (intakeFlow.isRunning()) {
                throw new IllegalStateException("Ingestion intake must remain stopped until startup recovery");
            }
            int recoveredRuns = recoverIngestRuns.getAsInt();
            int recoveredSources = recoverSources.recoverIncomplete().size();
            lifecycleAdmission.prepare();
            intakeFlow.start();
            if (!intakeFlow.isRunning()) {
                throw new IllegalStateException("Ingestion intake did not start after successful recovery");
            }
            Instant completedAt = clock.instant();
            lifecycleState.running(completedAt, recoveredRuns, recoveredSources);
            observe(() -> observer.recoveryCompleted(
                    startedAt, completedAt, recoveredRuns, recoveredSources));
        } catch (RuntimeException failure) {
            try {
                intakeFlow.stop();
            } catch (RuntimeException stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            Instant failedAt = clock.instant();
            lifecycleState.failed(failedAt, failure);
            observeFailure(() -> observer.recoveryFailed(startedAt, failedAt, failure), failure);
            throw failure;
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
