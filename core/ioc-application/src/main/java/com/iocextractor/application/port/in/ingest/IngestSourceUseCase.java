package com.iocextractor.application.port.in.ingest;

/**
 * Primary port for processing one whole source file in daemon mode.
 */
public interface IngestSourceUseCase {

    /**
     * Claims, extracts, writes canonical artifacts and archives one source.
     *
     * @param command source command
     * @return ingestion result
     */
    IngestSourceResult ingest(IngestSourceCommand command);
}
