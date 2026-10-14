package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite transaction primitives for short coordination-store write transactions. */
final class JdbcImmediateTransactions {

    private JdbcImmediateTransactions() {
    }

    static void begin(Connection connection) throws SQLException {
        execute(connection, "BEGIN IMMEDIATE");
    }

    static void commit(Connection connection) throws SQLException {
        execute(connection, "COMMIT");
    }

    static void rollback(Connection connection, Exception failure) {
        try {
            execute(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void execute(Connection connection, String command) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }
}
