package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractPin;

/** Application seam that produces one sealed, promotion-ready import stage. */
@FunctionalInterface
public interface DataframeImportStager {

    /** Recognizes, maps and seals one immutable delivery snapshot. */
    ImportStagingResult stage(ImportStagingCommand command);

    /** Restages only if the active contract still matches the durable pin. */
    default ImportStagingResult stagePinned(ImportStagingCommand command, ImportContractPin pin) {
        throw new DataframeImportConsistencyException(
                "Import stager does not support safe pinned restaging");
    }
}
