package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Runs startup compensation for incomplete ledger records in daemon mode.
 */
public final class IngestionRecoveryRunner implements ApplicationRunner {

    private final RecoverIngestionUseCase useCase;

    public IngestionRecoveryRunner(RecoverIngestionUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public void run(ApplicationArguments args) {
        useCase.recoverIncomplete();
    }
}
