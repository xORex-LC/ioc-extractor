package com.iocextractor.application.dataframeimport.model;

/** Durable forward-only recovery states of one dataframe import delivery. */
public enum ImportDeliveryState {
    /** Stable candidate admitted and assigned a global claim sequence. */
    DETECTED,
    /** Transport ownership acquisition is in progress or waiting for retry. */
    CLAIMING,
    /** Source object is owned by the service but local snapshot is not yet pinned. */
    CLAIMED,
    /** Private local snapshot digest and size are durable. */
    SNAPSHOT_PINNED,
    /** Exact-one contract version and fingerprint are durable. */
    CONTRACT_PINNED,
    /** Streaming parse/mapping into scratch storage is in progress. */
    STAGING,
    /** Closed staging reference and digest are durable. */
    STAGED,
    /** The head delivery may be inside or immediately around canonical promotion. */
    PROMOTING,
    /** Dataframe commit receipt proves canonical promotion completed. */
    CANONICAL_COMMITTED,
    /** Projection, report and source disposition are converging forward. */
    FINALIZING,
    /** Delivery has one immutable terminal outcome. */
    TERMINAL
}
