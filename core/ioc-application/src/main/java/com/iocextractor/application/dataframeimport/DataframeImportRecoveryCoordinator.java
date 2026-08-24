package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsUseCase;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;

import java.util.Objects;

/** Coalesces periodic durable recovery onto the same ordered global import lane. */
public final class DataframeImportRecoveryCoordinator {

    private final RecoverDataframeImportsUseCase recovery;
    private final KeyedSerialExecutor executor;
    private final int batchSize;

    /** Creates a non-blocking reconcile accelerator over durable recovery state. */
    public DataframeImportRecoveryCoordinator(RecoverDataframeImportsUseCase recovery,
                                              KeyedSerialExecutor executor,
                                              int batchSize) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (batchSize < 1) {
            throw new IllegalArgumentException("Import recovery batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    /** Submits one loss-tolerant bounded recovery pass. */
    public WorkAdmission nudge() {
        return executor.submit(DataframeImportWorkKeys.GLOBAL_LANE, this::recover);
    }

    private void recover() {
        RecoverDataframeImportsResult result = recovery.recover(batchSize);
        if (result.contradictions() > 0) {
            throw new DataframeImportConsistencyException(
                    "Managed import reconcile found contradictory durable evidence");
        }
    }
}
