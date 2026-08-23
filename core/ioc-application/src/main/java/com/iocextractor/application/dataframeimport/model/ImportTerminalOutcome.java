package com.iocextractor.application.dataframeimport.model;

/** Immutable terminal business outcome of one delivery occurrence. */
public enum ImportTerminalOutcome {
    /** Accepted set committed without rejected logical rows. */
    SUCCEEDED,
    /** Accepted set committed while invalid logical rows were reported. */
    COMPLETED_WITH_ERRORS,
    /** No canonical mutation was committed. */
    REJECTED
}
