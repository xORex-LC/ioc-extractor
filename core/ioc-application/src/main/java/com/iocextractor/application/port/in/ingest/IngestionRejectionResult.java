package com.iocextractor.application.port.in.ingest;

/**
 * Durable outcome of a terminal ingestion rejection.
 */
public enum IngestionRejectionResult {
    /** This call recorded the terminal failure. */
    REJECTED,
    /** The same source identity was already terminally rejected. */
    ALREADY_REJECTED
}
