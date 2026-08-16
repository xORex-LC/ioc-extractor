package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Shared SQLite transaction and lifecycle-state invariants. */
final class JdbcLifecycleTransactions {

    private JdbcLifecycleTransactions() {
    }

    static void acquireActiveWriteOwnership(Connection connection,
                                            String artifact,
                                            JdbcLifecycleTransactionObserver.Operation operation,
                                            JdbcLifecycleTransactionObserver observer) throws SQLException {
        observer.observe(JdbcLifecycleTransactionObserver.Phase.BEFORE_WRITE_OWNERSHIP, operation, artifact);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE canonical_lifecycle_control
                SET version = version
                WHERE singleton_id = 1
                  AND state = 'ACTIVE'
                """)) {
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Canonical lifecycle is not active");
            }
        }
        observer.observe(JdbcLifecycleTransactionObserver.Phase.AFTER_WRITE_OWNERSHIP, operation, artifact);
    }

    static void acquireActivatingWriteOwnership(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE canonical_lifecycle_control
                SET version = version
                WHERE singleton_id = 1
                  AND state = 'ACTIVATING'
                """)) {
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Canonical lifecycle is not activating: " + artifact);
            }
        }
    }

    static LifecycleActivationState readActivationState(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state
                FROM canonical_lifecycle_control
                WHERE singleton_id = 1
                """); ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IocExtractorException("Canonical lifecycle control state is missing");
            }
            return LifecycleActivationState.valueOf(resultSet.getString("state"));
        }
    }

    static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    static void restoreAutoCommit(Connection connection, boolean autoCommit, Exception original)
            throws SQLException {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException restoreFailure) {
            if (original == null) {
                throw restoreFailure;
            }
            original.addSuppressed(restoreFailure);
        }
    }
}
