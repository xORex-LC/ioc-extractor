package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;

import java.util.Optional;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceCapacity;

/** Driven port for per-delivery disk-backed, rebuildable staging. */
public interface ImportWorkspace {

    /**
     * Opens a new private writer; an existing incompatible workspace must not be overwritten silently.
     *
     * @param command pinned workspace identity
     * @return streaming writer
     */
    ImportWorkspaceWriter create(CreateImportWorkspaceCommand command);

    /** Explicitly discards rebuildable scratch state and opens a fresh writer. */
    ImportWorkspaceWriter rebuild(CreateImportWorkspaceCommand command);

    /** Verifies digest, metadata and SQLite integrity before read-only promotion. */
    ImportStage verifySealed(CreateImportWorkspaceCommand command, ImportStage expected);

    /** Adopts a physically sealed stage after a crash before its ledger checkpoint. */
    Optional<ImportStage> adoptSealed(ImportDeliveryId deliveryId,
                                      ImportSnapshot snapshot,
                                      ImportContractPin contract);

    /** Returns aggregate capacity state without exposing delivery paths. */
    ImportWorkspaceCapacity capacity();

    /** Idempotently removes all unpinned scratch files for a terminal delivery. */
    void discard(ImportDeliveryId deliveryId);
}
