package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ValidityDecision;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns at most one mutation session per configured artifact within one import transaction. */
final class JdbcCanonicalMutationSessions implements AutoCloseable {
    private final JdbcCanonicalMutationEngine engine;
    private final Connection connection;
    private final EffectiveTime asOf;
    private final ValidityDecision validity;
    private final Map<String, JdbcCanonicalMutationSession> sessions = new LinkedHashMap<>();
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    JdbcCanonicalMutationSessions(JdbcCanonicalMutationEngine engine, Connection connection,
                                   EffectiveTime asOf, ValidityDecision validity) {
        this.engine = engine;
        this.connection = connection;
        this.asOf = asOf;
        this.validity = validity;
    }

    JdbcCanonicalMutationSession forArtifact(DataframeArtifactSchema schema) throws SQLException {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("Canonical mutation sessions are closed");
        }
        var session = sessions.get(schema.artifactName());
        if (session == null) {
            session = engine.openSession(connection, schema, asOf, validity);
            sessions.put(schema.artifactName(), session);
        }
        return session;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Canonical mutation sessions belong to another thread");
        }
    }

    @Override
    public void close() throws SQLException {
        requireOwner();
        closed = true;
        SQLException failure = null;
        for (var session : sessions.values()) {
            try {
                session.close();
            } catch (SQLException closingFailure) {
                if (failure == null) {
                    failure = closingFailure;
                } else {
                    failure.addSuppressed(closingFailure);
                }
            }
        }
        sessions.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
