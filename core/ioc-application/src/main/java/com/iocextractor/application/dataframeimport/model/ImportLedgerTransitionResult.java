package com.iocextractor.application.dataframeimport.model;

/** Result of an idempotent delivery-ledger reservation or CAS transition. */
public enum ImportLedgerTransitionResult {
    /** Requested mutation was applied. */
    APPLIED,
    /** Exact requested state was already durable. */
    ALREADY_APPLIED,
    /** Delivery does not exist. */
    MISSING,
    /** Existing identity/state/version conflicts with the request. */
    CONFLICT
}
