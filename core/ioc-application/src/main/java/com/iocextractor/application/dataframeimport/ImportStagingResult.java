package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportStage;

import java.util.Objects;

/** Exact recognized contract and sealed workspace evidence. */
public record ImportStagingResult(ImportContractPin contract, ImportStage stage) {

    /** Requires both immutable checkpoints. */
    public ImportStagingResult {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(stage, "stage");
    }
}
