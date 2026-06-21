package com.iocextractor.application.ingest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Claimed source file owned by the ingestion pipeline.
 *
 * @param key source content key
 * @param originalPath path observed by the inbound adapter
 * @param processingPath path after the source was atomically claimed
 * @param detectedAt adapter observation timestamp
 */
public record SourceUnit(SourceKey key, Path originalPath, Path processingPath, Instant detectedAt) {

    public SourceUnit {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(originalPath, "originalPath");
        Objects.requireNonNull(processingPath, "processingPath");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }
}
