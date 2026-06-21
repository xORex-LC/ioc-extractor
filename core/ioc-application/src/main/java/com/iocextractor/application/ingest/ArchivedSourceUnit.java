package com.iocextractor.application.ingest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Minimal source view for recovery, where only a processing path is available
 * from the durable ledger.
 *
 * @param key source key
 * @param processingPath processing path to archive
 * @param detectedAt original detection timestamp
 */
public record ArchivedSourceUnit(SourceKey key, Path processingPath, Instant detectedAt) {

    public ArchivedSourceUnit {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(processingPath, "processingPath");
    }
}
