package com.iocextractor.adapter.out.store.jdbc;

/** Package-private deterministic test seam around SQLite lifecycle write ownership. */
@FunctionalInterface
interface JdbcLifecycleTransactionObserver {

    JdbcLifecycleTransactionObserver NOOP = (phase, operation, artifact) -> {
    };

    /** Observes a transaction boundary without owning production behavior. */
    void observe(Phase phase, Operation operation, String artifact);

    enum Phase {
        BEFORE_WRITE_OWNERSHIP,
        AFTER_WRITE_OWNERSHIP
    }

    enum Operation {
        CONFIRM,
        EXPIRE
    }
}
