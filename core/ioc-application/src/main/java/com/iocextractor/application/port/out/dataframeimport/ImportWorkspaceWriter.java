package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportStage;

/** One-delivery streaming workspace session; implementations own scratch transactions and sealing. */
public interface ImportWorkspaceWriter extends AutoCloseable {

    /**
     * Appends one complete logical row and all branches atomically within scratch storage.
     *
     * @param row mapped row
     */
    void append(ImportLogicalRow row);

    /**
     * Stores one rejected logical row using only safe issue metadata.
     *
     * @param row rejected row
     */
    void reject(ImportRejectedLogicalRow row);

    /**
     * Seals, indexes, closes and integrity-pins the workspace for read-only promotion.
     *
     * @return sealed stage evidence
     */
    ImportStage seal();

    /** Closes an unsealed workspace without promoting it to durable business truth. */
    @Override
    void close();
}
