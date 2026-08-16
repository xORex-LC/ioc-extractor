package com.iocextractor.application.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Minimal source view for recovery, where only a processing path is available
 * from the durable ledger.
 *
 * @param observationId durable delivery identity
 * @param key source content key
 * @param processingPath processing path to archive
 * @param detectedAt original detection timestamp
 */
public record ArchivedSourceUnit(ObservationId observationId,
                                 SourceKey key,
                                 Path processingPath,
                                 Instant detectedAt) {

    public ArchivedSourceUnit {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(processingPath, "processingPath");
    }

    /** Compatibility constructor for pre-P5 recovery records. */
    public ArchivedSourceUnit(SourceKey key, Path processingPath, Instant detectedAt) {
        this(ObservationId.legacy(key.value()), key, processingPath, detectedAt);
    }
}
