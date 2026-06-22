package com.iocextractor.application.ingest;

/**
 * Durable whole-file ingestion checkpoints. Each status is intentionally coarse
 * enough to support compensation without coupling the core to filesystem or CSV
 * implementation details.
 */
public enum IngestionStatus {
    CLAIMED,
    PARTITION_WRITTEN,
    LEDGER_RECORDED,
    SOURCE_ARCHIVED,
    AGGREGATED,
    FAILED
}
