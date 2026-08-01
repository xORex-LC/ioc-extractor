package com.iocextractor.application.ingest;

/** Outcome of an expected-state ingestion-ledger transition. */
public enum IngestionLedgerTransition {
    /** This call durably changed the state. */
    APPLIED,
    /** The requested target state was already durable. */
    ALREADY_APPLIED,
    /** A different durable state prevents the requested transition. */
    CONFLICT,
    /** The transition requires a record, but none exists. */
    MISSING;

    /** Returns true when the requested target state is durable. */
    public boolean completed() {
        return this == APPLIED || this == ALREADY_APPLIED;
    }
}
