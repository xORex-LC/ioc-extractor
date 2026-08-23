package com.iocextractor.application.port.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Command to process one source file detected by an inbound adapter.
 *
 * @param source original source path
 * @param observationId durable identity of this delivered occurrence
 * @param key source content key
 * @param detectedAt adapter observation timestamp
 */
public record IngestSourceCommand(Path source,
                                  ObservationId observationId,
                                  SourceKey key,
                                  Instant detectedAt) {

    public IngestSourceCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }

    /** Compatibility constructor for a recoverable delivery identified by its content key. */
    public IngestSourceCommand(Path source, SourceKey key, Instant detectedAt) {
        this(source, ObservationId.legacy(key.value()), key, detectedAt);
    }
}
