package com.iocextractor.application.pipeline;

/**
 * Stable machine-readable identifiers for ETL stages.
 */
public enum StageName {
    INITIAL,
    READ_SOURCE,
    REFANG,
    EXTRACT,
    ATTRIBUTE,
    DEDUPLICATE,
    WRITE_ARTIFACTS
}
