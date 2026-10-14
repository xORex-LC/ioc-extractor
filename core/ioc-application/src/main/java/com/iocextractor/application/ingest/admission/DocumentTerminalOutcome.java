package com.iocextractor.application.ingest.admission;

/** Safe terminal disposition retained until registration finalization completes. */
public enum DocumentTerminalOutcome {
    SUCCEEDED,
    REJECTED,
    QUARANTINED
}
