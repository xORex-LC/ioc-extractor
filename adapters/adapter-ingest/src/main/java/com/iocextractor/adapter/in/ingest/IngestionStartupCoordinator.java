package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.Lifecycle;

import java.time.Clock;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Owns the daemon startup barrier between durable recovery and file intake.
 * The inbound flow is configured not to auto-start and is opened only after
 * both recovery steps complete successfully.
 */
public final class IngestionStartupCoordinator implements ApplicationRunner {

    private final IntSupplier recoverIngestRuns;
    private final RecoverIngestionUseCase recoverSources;
    private final Lifecycle intakeFlow;
    private final IngestionLifecycleState lifecycleState;
    private final Clock clock;

    public IngestionStartupCoordinator(IntSupplier recoverIngestRuns,
                                       RecoverIngestionUseCase recoverSources,
                                       Lifecycle intakeFlow,
                                       IngestionLifecycleState lifecycleState,
                                       Clock clock) {
        this.recoverIngestRuns = Objects.requireNonNull(recoverIngestRuns, "recoverIngestRuns");
        this.recoverSources = Objects.requireNonNull(recoverSources, "recoverSources");
        this.intakeFlow = Objects.requireNonNull(intakeFlow, "intakeFlow");
        this.lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void run(ApplicationArguments args) {
        lifecycleState.recoveryStarted(clock.instant());
        try {
            if (intakeFlow.isRunning()) {
                throw new IllegalStateException("Ingestion intake must remain stopped until startup recovery");
            }
            int recoveredRuns = recoverIngestRuns.getAsInt();
            int recoveredSources = recoverSources.recoverIncomplete().size();
            intakeFlow.start();
            if (!intakeFlow.isRunning()) {
                throw new IllegalStateException("Ingestion intake did not start after successful recovery");
            }
            lifecycleState.running(clock.instant(), recoveredRuns, recoveredSources);
        } catch (RuntimeException failure) {
            try {
                intakeFlow.stop();
            } catch (RuntimeException stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            lifecycleState.failed(clock.instant(), failure);
            throw failure;
        }
    }
}
