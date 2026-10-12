package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Planner maintenance uses its own admitted tick; it never changes business epochs or TTL authority. */
final class JdbcPublicationStatistics {
    private static final long INTERVAL_MILLIS = java.time.Duration.ofHours(1).toMillis();
    private JdbcPublicationStatistics() { }

    static JdbcPublicationVersionCleanup.Page optimize(Connection connection, long now) throws SQLException {
        long previous = JdbcPublicationCleanupCursor.read(connection, "$statistics");
        if (previous != 0 && (now < previous || now - previous < INTERVAL_MILLIS)) {
            return new JdbcPublicationVersionCleanup.Page(0, 0, true);
        }
        // SQLite 3.46+ bounds analysis internally. This is not a hard latency guarantee.
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA optimize=0x10002"); }
        JdbcPublicationCleanupCursor.write(connection, "$statistics", Math.max(1, now));
        return new JdbcPublicationVersionCleanup.Page(0, 0, true);
    }
}
