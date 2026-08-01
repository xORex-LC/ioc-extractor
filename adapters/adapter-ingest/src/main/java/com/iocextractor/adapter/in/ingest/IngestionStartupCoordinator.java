package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.Lifecycle;

import java.util.Objects;

/**
 * Owns the daemon startup barrier between durable recovery and file intake.
 * The inbound flow is configured not to auto-start and is opened only after
 * both recovery steps complete successfully.
 */
public final class IngestionStartupCoordinator implements ApplicationRunner {

    private final Runnable recoverIngestRuns;
    private final RecoverIngestionUseCase recoverSources;
    private final Lifecycle intakeFlow;

    public IngestionStartupCoordinator(Runnable recoverIngestRuns,
                                       RecoverIngestionUseCase recoverSources,
                                       Lifecycle intakeFlow) {
        this.recoverIngestRuns = Objects.requireNonNull(recoverIngestRuns, "recoverIngestRuns");
        this.recoverSources = Objects.requireNonNull(recoverSources, "recoverSources");
        this.intakeFlow = Objects.requireNonNull(intakeFlow, "intakeFlow");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (intakeFlow.isRunning()) {
            throw new IllegalStateException("Ingestion intake must remain stopped until startup recovery");
        }
        recoverIngestRuns.run();
        recoverSources.recoverIncomplete();
        intakeFlow.start();
    }
}
