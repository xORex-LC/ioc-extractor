package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsUseCase;
import com.iocextractor.application.observation.ManagedImportObservationAdmission;

import java.util.Objects;

/** Bounded recovery that reconciles ownership before draining durable heads. */
public final class DataframeImportRecoveryService implements RecoverDataframeImportsUseCase {

    private final RecoverDataframeImportsUseCase admissionRecovery;
    private final ProcessNextDataframeImportUseCase processor;
    private final ManagedImportObservationAdmission observationAdmission;

    /** Creates a recovery facade over claim and global-lane state machines. */
    public DataframeImportRecoveryService(RecoverDataframeImportsUseCase admissionRecovery,
                                          ProcessNextDataframeImportUseCase processor) {
        this(admissionRecovery, processor, null);
    }

    /** Creates recovery with bounded terminal-handshake reconciliation. */
    public DataframeImportRecoveryService(RecoverDataframeImportsUseCase admissionRecovery,
                                          ProcessNextDataframeImportUseCase processor,
                                          ManagedImportObservationAdmission observationAdmission) {
        this.admissionRecovery = Objects.requireNonNull(admissionRecovery, "admissionRecovery");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.observationAdmission = observationAdmission;
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
        if (observationAdmission != null) {
            var references = observationAdmission.recover(limit);
            examined += references.size();
            advanced += (int) references.stream().filter(reference -> reference.registrationFinalized()).count();
        }
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
