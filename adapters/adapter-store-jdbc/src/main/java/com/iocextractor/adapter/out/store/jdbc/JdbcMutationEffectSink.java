package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import java.sql.Connection;
import java.sql.SQLException;

/** Adapter-internal transactional capture of native mutation results; never a second mutation engine. */
@FunctionalInterface
interface JdbcMutationEffectSink extends AutoCloseable {
    JdbcMutationEffectSink NOOP = (artifact, outcome, renewsValidity) -> { };

    void record(String artifact, CanonicalRecordMutationOutcome outcome, boolean renewsValidity) throws SQLException;

    @Override default void close() throws SQLException { }

    /** Opens a sink on the mutation transaction's connection; it must not commit or close that connection. */
    @FunctionalInterface
    interface Factory {
        Factory NONE = connection -> NOOP;
        JdbcMutationEffectSink open(Connection connection) throws SQLException;
    }
}
