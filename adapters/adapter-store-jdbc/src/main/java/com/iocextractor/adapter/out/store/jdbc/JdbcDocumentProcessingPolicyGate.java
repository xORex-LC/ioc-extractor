package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;

/** Pins the active document policy until every older admission has drained. */
public final class JdbcDocumentProcessingPolicyGate {
    private final DataSource dataSource;

    public JdbcDocumentProcessingPolicyGate(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Changes the active policy only when both external and journaled work is drained. */
    public void ensure(String fingerprint, BooleanSupplier externalWorkDrained) {
        Objects.requireNonNull(externalWorkDrained, "externalWorkDrained");
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Document policy fingerprint must be SHA-256 hex");
        }
        try (Connection connection = dataSource.getConnection()) {
            String current = current(connection);
            if (Objects.equals(current, fingerprint)) {
                return;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Document processing policy gate could not inspect service storage", failure);
        }
        if (!externalWorkDrained.getAsBoolean()) {
            throw new IllegalStateException(
                    "Document processing policy changed with unfinished intake; drain using the previous policy");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String current = current(connection);
                if (Objects.equals(current, fingerprint)) {
                    connection.commit();
                    return;
                }
                if (hasJournaledWork(connection)) {
                    throw new IllegalStateException(
                            "Document processing policy changed with unfinished intake; drain using the previous policy");
                }
                if (current == null) {
                    try (var insert = connection.prepareStatement(
                            "INSERT INTO document_processing_policy(id, policy_fingerprint) VALUES (1, ?)")) {
                        insert.setString(1, fingerprint);
                        insert.executeUpdate();
                    }
                } else {
                    try (var update = connection.prepareStatement(
                            "UPDATE document_processing_policy SET policy_fingerprint = ? WHERE id = 1")) {
                        update.setString(1, fingerprint);
                        update.executeUpdate();
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Document processing policy gate could not inspect service storage", failure);
        }
    }

    private static String current(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT policy_fingerprint FROM document_processing_policy WHERE id = 1");
             var rows = query.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static boolean hasJournaledWork(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT 1 FROM document_admission "
                        + "WHERE phase <> 'TERMINAL' OR registration_finalized = 0 LIMIT 1");
             var rows = query.executeQuery()) {
            return rows.next();
        }
    }
}
