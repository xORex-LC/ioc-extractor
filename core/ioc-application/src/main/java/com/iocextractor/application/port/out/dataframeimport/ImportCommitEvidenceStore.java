package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportCommitEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;

import java.util.Optional;

/** Dataframe-owned receipt evidence used for forward recovery and retention. */
public interface ImportCommitEvidenceStore {

    /** Returns bounded safe evidence when canonical promotion committed. */
    Optional<ImportCommitEvidence> find(ImportDeliveryId deliveryId);

    /** Deletes a receipt only after terminal service state no longer needs it. */
    void purge(ImportDeliveryId deliveryId);
}
