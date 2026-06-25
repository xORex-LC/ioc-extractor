package com.iocextractor.application.port.in.ingest;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.port.in.ExtractionResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of processing one source file through the ingestion pipeline.
 *
 * @param key source content key
 * @param status final status for this command
 * @param duplicate true when the source key was already archived
 * @param extractionResult extraction summary, absent for duplicate skips
 */
public record IngestSourceResult(SourceKey key,
                                 IngestionStatus status,
                                 boolean duplicate,
                                 ExtractionResult extractionResult) {

    public IngestSourceResult {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
    }

    public Optional<ExtractionResult> extractionResultOptional() {
        return Optional.ofNullable(extractionResult);
    }
}
