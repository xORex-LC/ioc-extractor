package com.iocextractor.application.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable ledger snapshot for a source key.
 *
 * @param observationId durable delivery identity and ledger key
 * @param key source content key
 * @param status current durable checkpoint
 * @param originalPath original source path
 * @param processingPath claimed processing path
 * @param archivedPath final done/failed path, when available
 * @param detectedAt source observation timestamp
 * @param updatedAt ledger update timestamp
 * @param reason failure reason, when available
 */
public record IngestionRecord(ObservationId observationId,
                              SourceKey key,
                              IngestionStatus status,
                              Path originalPath,
                              Path processingPath,
                              Path archivedPath,
                              Instant detectedAt,
                              Instant updatedAt,
                              String reason) {

    public IngestionRecord {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(originalPath, "originalPath");
        Objects.requireNonNull(processingPath, "processingPath");
    }

    /** Compatibility constructor for pre-P5 ledger fixtures. */
    public IngestionRecord(SourceKey key,
                           IngestionStatus status,
                           Path originalPath,
                           Path processingPath,
                           Path archivedPath,
                           Instant detectedAt,
                           Instant updatedAt,
                           String reason) {
        this(ObservationId.legacy(key.value()), key, status, originalPath, processingPath,
                archivedPath, detectedAt, updatedAt, reason);
    }

    /** Compares the durable physical outcome, leaving update timestamps out of retry identity. */
    public boolean matchesRejection(SourceUnit source, Path failedPath, String code) {
        return status == IngestionStatus.FAILED && observationId.equals(source.observationId())
                && key.equals(source.key()) && originalPath.equals(source.originalPath())
                && processingPath.equals(source.processingPath()) && failedPath.equals(archivedPath)
                && code.equals(reason);
    }

    public Optional<Path> archivedPathOptional() {
        return Optional.ofNullable(archivedPath);
    }

    public Optional<String> reasonOptional() {
        return Optional.ofNullable(reason);
    }
}
