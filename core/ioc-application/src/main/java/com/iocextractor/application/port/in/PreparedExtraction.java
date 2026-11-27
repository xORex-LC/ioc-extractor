package com.iocextractor.application.port.in;

import com.iocextractor.application.artifact.DocumentPreparationSeal;
import com.iocextractor.application.artifact.DocumentWriteIdentity;

import java.util.Set;

/** Owned sealed precommit state. Only promotion reserves IDs or changes canonical truth. */
public interface PreparedExtraction extends AutoCloseable {
    ExtractionResult promote();

    /**
     * Promotes only the specified artifacts of an admitted run. The full plan remains
     * bound to the supplied identity, including its artifacts committed earlier.
     */
    default ExtractionResult promote(DocumentWriteIdentity identity, Set<String> missingArtifacts) {
        throw new UnsupportedOperationException("Managed document promotion is unavailable");
    }

    /** Pins the checkpoint-approved plan across owner close, retry and restart. */
    default DocumentPreparationSeal seal() {
        throw new UnsupportedOperationException("Document preparation cannot be pinned");
    }

    @Override
    void close();
}
