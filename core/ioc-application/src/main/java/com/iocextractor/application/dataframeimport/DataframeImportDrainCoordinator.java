package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;

import java.util.Objects;

/** Coalesced in-process accelerator for the one durable global import lane. */
public final class DataframeImportDrainCoordinator {

    private final ProcessNextDataframeImportUseCase processor;
    private final KeyedSerialExecutor executor;
    private final int batchSize;

    /** Creates a bounded drain; durable head selection remains in the use case. */
    public DataframeImportDrainCoordinator(ProcessNextDataframeImportUseCase processor,
                                           KeyedSerialExecutor executor,
                                           int batchSize) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (batchSize < 1) {
            throw new IllegalArgumentException("Import drain batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    /** Submits one loss-tolerant global drain hint. */
    public WorkAdmission nudge() {
        return executor.submit(DataframeImportWorkKeys.GLOBAL_LANE, this::drain);
    }

    private void drain() {
        for (int index = 0; index < batchSize; index++) {
            ProcessNextDataframeImportResult result = processor.processNext();
            if (!result.workPerformed()) {
                return;
            }
        }
        nudge();
    }
}
