package com.iocextractor.adapter.out.store.jdbc;

/** Unit-level qualification boundary; contains no IOC payload and never grants commit authority. */
@FunctionalInterface
interface JdbcPublicationObserver {
    JdbcPublicationObserver NOOP = (phase, attempt, chunk) -> { };
    enum Phase { PREPARED, HIDDEN_CHUNK, SEALED, BEFORE_PUBLICATION, COMMITTED, REBUILD }
    void observe(Phase phase, int attempt, int chunk);
}
