package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Compact, permanent replay proof. Retirement never forgets a promotion identity or its exact pins. */
final class JdbcPublicationRetiredIdentity {
    private JdbcPublicationRetiredIdentity() { }

    static void initialize(Connection connection) throws SQLException {
        JdbcPublicationTable.execute(connection, "CREATE TABLE publication_retired_identity("
                + "identity TEXT PRIMARY KEY,pins TEXT NOT NULL,publication_sequence INTEGER NOT NULL,"
                + "effective_millis INTEGER NOT NULL,receipt TEXT NOT NULL) WITHOUT ROWID");
        JdbcPublicationTable.execute(connection, "CREATE TRIGGER publication_replay_guard BEFORE INSERT ON publication_unit"
                + " WHEN EXISTS(SELECT 1 FROM publication_retired_identity WHERE identity=NEW.identity)"
                + " BEGIN SELECT RAISE(ABORT,'Retired publication identity cannot be republished'); END");
    }

    /** Older candidate databases retain their existing header policy; reopening never performs implicit migration. */
    static boolean supported(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sqlite_schema WHERE (type='table' AND name='publication_retired_identity')"
                        + " OR(type='trigger' AND name='publication_replay_guard')")) {
            if (!rows.next()) { throw new SQLException("Missing retention capability result"); }
            int count = rows.getInt(1);
            if (count == 1) { throw new SQLException("Incomplete retired publication identity capability"); }
            return count == 2;
        }
    }

    static void retain(Connection connection, long unit) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO publication_retired_identity"
                + " SELECT identity,pins,publication_sequence,effective_millis,receipt FROM publication_unit"
                + " WHERE id=? AND state='COMMITTED' AND (identity LIKE 'document:%' OR identity LIKE 'import:%')")) {
            statement.setLong(1, unit);
            if (statement.executeUpdate() != 1) { throw new SQLException("Missing committed promotion replay proof"); }
        }
    }
}
