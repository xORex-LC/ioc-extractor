package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Durable keyset progress for physical cleanup; never changes business epochs or reader visibility. */
final class JdbcPublicationCleanupCursor {
    private JdbcPublicationCleanupCursor() { }

    static void initialize(Connection connection) throws SQLException {
        JdbcPublicationTable.execute(connection, "CREATE TABLE publication_cleanup_cursor("
                + "family TEXT PRIMARY KEY,position INTEGER NOT NULL CHECK(position>=0)) WITHOUT ROWID");
    }

    static long read(Connection connection, String family) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT position FROM publication_cleanup_cursor WHERE family=?")) {
            statement.setString(1, family);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }

    static void write(Connection connection, String family, long position) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO publication_cleanup_cursor VALUES(?,?)"
                + " ON CONFLICT(family) DO UPDATE SET position=excluded.position")) {
            statement.setString(1, family); statement.setLong(2, position); statement.executeUpdate();
        }
    }

    static void requireLimit(int limit) {
        if (limit < 1 || limit > 256) { throw new IllegalArgumentException("Publication cleanup is bounded to 256 rows"); }
    }

    /** Counters commit with deletions and cursor progress; a rollback records no useful service. */
    static void record(Connection connection, String family, JdbcPublicationVersionCleanup.Page page, long nanos)
            throws SQLException {
        add(connection, "$service." + family + ".pages", 1);
        add(connection, "$service." + family + ".scanned", page.scanned());
        add(connection, "$service." + family + ".removed", page.removed());
        add(connection, "$service." + family + ".nanos", nanos);
        if (page.wrapped()) { add(connection, "$service." + family + ".wraps", 1); }
    }

    private static void add(Connection connection, String family, long value) throws SQLException {
        write(connection, family, Math.addExact(read(connection, family), value));
    }
}
