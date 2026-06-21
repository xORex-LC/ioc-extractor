package com.iocextractor.application.port.in.ingest;

import com.iocextractor.application.ingest.SourceKey;

/**
 * Primary port for final rejection after adapter-level retries are exhausted.
 */
public interface RejectIngestionUseCase {

    /**
     * Moves a claimed source to the failed lifecycle state and records the
     * terminal failure.
     *
     * @param key source key
     * @param reason failure reason
     */
    void reject(SourceKey key, String reason);
}
