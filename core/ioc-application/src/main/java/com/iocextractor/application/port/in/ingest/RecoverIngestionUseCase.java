package com.iocextractor.application.port.in.ingest;

import java.util.List;

/**
 * Primary port for startup compensation of incomplete ingestion records.
 */
public interface RecoverIngestionUseCase {

    /**
     * Attempts to complete records left in recoverable intermediate states.
     *
     * @return recovery results
     */
    List<IngestSourceResult> recoverIncomplete();
}
