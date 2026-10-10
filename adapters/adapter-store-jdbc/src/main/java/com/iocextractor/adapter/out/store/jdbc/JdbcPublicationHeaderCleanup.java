package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Reclaims unreferenced control headers only. Promotion pins need durable coordination acknowledgement. */
final class JdbcPublicationHeaderCleanup {
    private JdbcPublicationHeaderCleanup() { }

    static JdbcPublicationVersionCleanup.Page purge(Connection connection, JdbcTypedPublicationStore store, int limit)
            throws SQLException {
        long after = JdbcPublicationCleanupCursor.read(connection, "$headers");
        var units = new ArrayList<Long>(limit);
        try (var statement = connection.prepareStatement("SELECT id FROM publication_unit WHERE id>? ORDER BY id LIMIT ?")) {
            statement.setLong(1, after); statement.setInt(2, limit);
            try (var rows = statement.executeQuery()) { while (rows.next()) { units.add(rows.getLong(1)); } }
        }
        int removed = 0;
        for (long unit : units) {
            if (removed + 2 > limit || !eligible(connection, store, unit)) { continue; }
            try (var statement = connection.prepareStatement("DELETE FROM publication_attempt WHERE unit_id=?")) {
                statement.setLong(1, unit); removed += statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("DELETE FROM publication_unit WHERE id=?")) {
                statement.setLong(1, unit); removed += statement.executeUpdate();
            }
        }
        boolean wrapped = units.size() < limit;
        JdbcPublicationCleanupCursor.write(connection, "$headers", wrapped ? 0 : units.getLast());
        return new JdbcPublicationVersionCleanup.Page(units.size(), removed, wrapped);
    }

    private static boolean eligible(Connection connection, JdbcTypedPublicationStore store, long unit) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM publication_unit WHERE id=?"
                + " AND identity LIKE 'control:%' AND state IN('COMMITTED','CANCELLED')"
                + " AND NOT EXISTS(SELECT 1 FROM publication_logical_owner o WHERE o.unit=publication_unit.identity)")) {
            statement.setLong(1, unit);
            try (var rows = statement.executeQuery()) { if (!rows.next()) { return false; } }
        }
        for (var table : store.tables()) {
            if (references(connection, table.versions(), "unit_id", unit)) { return false; }
            if (store.artifacts().contains(table.name()) && references(connection, table.versions(), "deadline_anchor", unit)) { return false; }
        }
        for (String table : List.of("publication_fact", "publication_chunk", "publication_dependency")) {
            if (references(connection, table, "unit_id", unit)) { return false; }
        }
        if (references(connection, "publication_fact", "deadline_anchor", unit)) { return false; }
        try (var statement = connection.prepareStatement("SELECT stamp FROM publication_attempt WHERE unit_id=? LIMIT 2")) {
            statement.setLong(1, unit);
            try (var rows = statement.executeQuery()) { return !rows.next() || !rows.next(); }
        }
    }

    private static boolean references(Connection connection, String table, String column, long unit) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM " + JdbcSql.quote(table)
                + " WHERE " + JdbcSql.quote(column) + "=? LIMIT 1")) {
            statement.setLong(1, unit);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
}
