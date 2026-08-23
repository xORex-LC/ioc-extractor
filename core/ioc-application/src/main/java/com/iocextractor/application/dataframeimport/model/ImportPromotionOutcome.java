package com.iocextractor.application.dataframeimport.model;

/** Idempotent outcome of atomic canonical import promotion. */
public enum ImportPromotionOutcome {
    /** This invocation committed the dataframe transaction. */
    COMMITTED,
    /** An identical delivery receipt already proved the commit. */
    ALREADY_COMMITTED
}
