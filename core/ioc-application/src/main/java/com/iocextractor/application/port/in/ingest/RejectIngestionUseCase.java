package com.iocextractor.application.port.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;

/**
 * Primary port for durable final rejection after adapter-level retries are exhausted.
 */
public interface RejectIngestionUseCase {

    /**
     * Moves an owned source to the failed lifecycle state when possible and
     * records the terminal failure. Repeating the same source identity is an
     * idempotent no-op.
     *
     * @param key source key
     * @param reason failure reason
     * @return whether this call created the durable rejection
     */
    IngestionRejectionResult reject(SourceKey key, String reason);

    /**
     * Rejects one durable delivery attempt without conflating it with another
     * delivery of the same content. Legacy implementations retain their
     * content-key behavior through the default adapter.
     */
    default IngestionRejectionResult reject(ObservationId observationId,
                                            SourceKey key,
                                            String reason) {
        return reject(key, reason);
    }
}
