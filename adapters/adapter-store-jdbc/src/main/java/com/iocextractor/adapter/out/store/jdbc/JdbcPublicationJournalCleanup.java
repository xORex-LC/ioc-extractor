package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Reclaims acknowledged transfer evidence, never headers/pins, TTL anchors or the current attempt identity. */
final class JdbcPublicationJournalCleanup {
    private JdbcPublicationJournalCleanup() { }

    static JdbcPublicationVersionCleanup.Page purge(Connection connection, int limit) throws SQLException {
        long after = JdbcPublicationCleanupCursor.read(connection, "$journal");
        try (var statement = connection.prepareStatement("SELECT a.stamp,a.unit_id,a.fence,u.fence,u.state"
                + " FROM publication_attempt a JOIN publication_unit u ON u.id=a.unit_id WHERE a.stamp>? ORDER BY a.stamp LIMIT 1")) {
            statement.setLong(1, after);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    JdbcPublicationCleanupCursor.write(connection, "$journal", 0);
                    return new JdbcPublicationVersionCleanup.Page(0, 0, true);
                }
                long stamp = rows.getLong(1), unit = rows.getLong(2), fence = rows.getLong(3);
                boolean obsolete = fence != rows.getLong(4);
                String state = rows.getString(5);
                int removed = 0;
                boolean drained = true;
                if (obsolete || state.equals("COMMITTED") || state.equals("CANCELLED")) {
                    removed = removeChildren(connection, unit, fence, limit);
                    drained = empty(connection, unit, fence);
                    if (drained && obsolete && removed < limit) {
                        try (var delete = connection.prepareStatement("DELETE FROM publication_attempt WHERE stamp=?")) {
                            delete.setLong(1, stamp); removed += delete.executeUpdate();
                        }
                    }
                }
                JdbcPublicationCleanupCursor.write(connection, "$journal", drained ? stamp : stamp - 1);
                return new JdbcPublicationVersionCleanup.Page(1, removed, false);
            }
        }
    }

    private static int removeChildren(Connection connection, long unit, long fence, int limit) throws SQLException {
        int removed = delete(connection, "publication_fact", "family,scope,fact_key", unit, fence, limit);
        removed += delete(connection, "publication_chunk", "ordinal", unit, fence, limit - removed);
        if (!hasRows(connection, "publication_fact", unit, fence)) {
            removed += delete(connection, "publication_dependency", "scope", unit, fence, limit - removed);
        }
        return removed;
    }

    private static int delete(Connection connection, String table, String keys, long unit, long fence, int limit) throws SQLException {
        if (limit == 0) { return 0; }
        // Table/key expressions are fixed adapter-owned constants; values are always bound.
        String sql = "DELETE FROM " + table + " WHERE unit_id=? AND fence=? AND (" + keys + ") IN(SELECT "
                + keys + " FROM " + table + " WHERE unit_id=? AND fence=? ORDER BY " + keys + " LIMIT ?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, unit); statement.setLong(2, fence); statement.setLong(3, unit);
            statement.setLong(4, fence); statement.setInt(5, limit); return statement.executeUpdate();
        }
    }

    private static boolean empty(Connection connection, long unit, long fence) throws SQLException {
        return !hasRows(connection, "publication_fact", unit, fence)
                && !hasRows(connection, "publication_chunk", unit, fence)
                && !hasRows(connection, "publication_dependency", unit, fence);
    }

    private static boolean hasRows(Connection connection, String table, long unit, long fence) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE unit_id=? AND fence=? LIMIT 1")) {
            statement.setLong(1, unit); statement.setLong(2, fence);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
}
