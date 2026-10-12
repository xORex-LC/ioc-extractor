package com.iocextractor.adapter.out.store.jdbc;

/** Unit-level qualification boundary; contains no IOC payload and never grants commit authority. */
@FunctionalInterface
interface JdbcPublicationObserver {
    JdbcPublicationObserver NOOP = (phase, attempt, chunk) -> { };
    enum Phase { PREPARED, BEFORE_HIDDEN_CHUNK, HIDDEN_CHUNK, SEALED, BEFORE_PUBLICATION, PUBLICATION_STAGED, COMMITTED, REBUILD }
    enum Timing { CONTROL_GATE_WAIT, CONTROL_GATE_HOLD, WORKSPACE_ADMISSION, SCHEMA, HYDRATION, NATIVE_PREPARATION,
        DESCRIPTION, TRANSFER, CONNECTION_CHECKOUT, TRANSACTION, COMMIT }
    void observe(Phase phase, int attempt, int chunk);

    /** Monotonic operation durations; observation never supplies publication authority. */
    default void elapsed(Timing timing, long nanos) { }

    static <T> T measure(JdbcPublicationObserver observer, Timing timing, TimedWork<T> work) throws java.sql.SQLException {
        if (observer == NOOP) { return work.run(); }
        long started = System.nanoTime();
        try { return work.run(); }
        finally { observer.elapsed(timing, System.nanoTime() - started); }
    }

    @FunctionalInterface interface TimedWork<T> { T run() throws java.sql.SQLException; }
}
