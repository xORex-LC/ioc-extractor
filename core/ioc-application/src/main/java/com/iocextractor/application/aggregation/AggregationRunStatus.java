package com.iocextractor.application.aggregation;

/**
 * Durable aggregation saga checkpoints.
 */
public enum AggregationRunStatus {
    STARTED,
    DB_COMMITTED,
    PROJECTION_COMPLETED,
    COMPLETED,
    FAILED
}
