package com.iocextractor.adapter.out.store.jdbc;

/** Test/diagnostic seam at durable phases of one canonical import transaction. */
@FunctionalInterface
interface JdbcCanonicalImportObserver {

    JdbcCanonicalImportObserver NOOP = phase -> { };

    /** Called after the named phase; throwing aborts unless commit already completed. */
    void after(Phase phase);

    /** Ordered failure-injection phases. */
    enum Phase {
        STAGE_ATTACHED,
        ACTIVE_MATCHES_PLANNED,
        MERGES_PLANNED,
        FAILURE_POLICY_PASSED,
        CANONICAL_MUTATIONS_APPLIED,
        SLOTS_RECONCILED,
        REVISIONS_ADVANCED,
        RECEIPT_WRITTEN,
        BEFORE_COMMIT,
        AFTER_COMMIT
    }
}
