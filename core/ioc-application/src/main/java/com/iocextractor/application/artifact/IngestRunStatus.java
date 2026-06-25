package com.iocextractor.application.artifact;

/**
 * Durable per-file ingest saga checkpoints.
 */
public enum IngestRunStatus {
    STARTED,
    DB_COMMITTED,
    PROJECTION_COMPLETED,
    COMPLETED,
    FAILED
}
