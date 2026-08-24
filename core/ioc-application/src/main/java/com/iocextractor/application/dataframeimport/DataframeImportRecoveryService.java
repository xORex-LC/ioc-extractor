package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsUseCase;

import java.util.Objects;

/** Bounded recovery that reconciles ownership before draining durable heads. */
public final class DataframeImportRecoveryService implements RecoverDataframeImportsUseCase {

    private final RecoverDataframeImportsUseCase admissionRecovery;
    private final ProcessNextDataframeImportUseCase processor;

    /** Creates a recovery facade over claim and global-lane state machines. */
    public DataframeImportRecoveryService(RecoverDataframeImportsUseCase admissionRecovery,
                                          ProcessNextDataframeImportUseCase processor) {
        this.admissionRecovery = Objects.requireNonNull(admissionRecovery, "admissionRecovery");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Override
    public RecoverDataframeImportsResult recover(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Import recovery limit must be positive");
        }
        RecoverDataframeImportsResult claims = admissionRecovery.recover(limit);
        int examined = claims.examined();
        int advanced = claims.advanced();
        int contradictions = claims.contradictions();
        for (int index = 0; index < limit; index++) {
            examined++;
            try {
                ProcessNextDataframeImportResult result = processor.processNext();
                if (!result.workPerformed()) {
                    break;
                }
                advanced++;
            } catch (DataframeImportConsistencyException contradiction) {
                contradictions++;
                break;
            }
        }
        return new RecoverDataframeImportsResult(examined, advanced, contradictions);
    }
}
