package com.iocextractor.adapter.out.store.jdbc;

/** Unit-level qualification boundary; contains no IOC payload and never grants commit authority. */
@FunctionalInterface
interface JdbcPublicationObserver {
    JdbcPublicationObserver NOOP = (phase, attempt, chunk) -> { };
    enum Phase { PREPARED, BEFORE_HIDDEN_CHUNK, HIDDEN_CHUNK, SEALED, BEFORE_PUBLICATION, PUBLICATION_STAGED, COMMITTED, REBUILD }
    void observe(Phase phase, int attempt, int chunk);
}
