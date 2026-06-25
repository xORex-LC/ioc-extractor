package com.iocextractor.application.aggregation;

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
