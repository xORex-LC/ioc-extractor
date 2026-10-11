package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Indexed, thread-confined cursor over actual private DML, with a borrowed sealed snapshot. */
final class JdbcPrivateChanges {
    private JdbcPrivateChanges() { }

    record Effect(JdbcPublicationTable table, List<Object> values, List<Object> previous, boolean deleted,
                  boolean renewsValidity) {
        Effect {
            values = Collections.unmodifiableList(new ArrayList<>(values));
            previous = previous == null ? null : Collections.unmodifiableList(new ArrayList<>(previous));
        }

        Object value(String column) { return values.get(table.columns().indexOf(column)); }
        Object oldValue(String column) {
            return previous == null ? null : previous.get(table.columns().indexOf(column));
        }
    }

    record Cursor(boolean started, long nativeAddress) {
        static final Cursor START = new Cursor(false, 0);
    }

    /** Pages cannot materialize a table or fan-in in heap; the caller bounds payload bytes separately. */
    static Cursor read(Connection connection, JdbcPublicationTable table, Cursor after, EffectConsumer consumer)
            throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException("Private effects require a pinned transaction");
        }
        boolean emptyFamily = emptyFamily(connection, table);
        boolean journal = table.columns().contains("_lifecycle_id") && table.columns().contains("row_key")
                && !table.name().endsWith("_history") && hasJournal(connection);
        String sql = selectSql(table, emptyFamily, journal, after.started());
        long last = 0;
        boolean advanced = false;
        try (var statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (journal) { statement.setString(parameter++, table.name()); }
            if (after.started()) { statement.setLong(parameter, after.nativeAddress()); }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    last = rows.getLong(1); advanced = true;
                    emit(table, rows, consumer);
                }
            }
        }
        return advanced ? new Cursor(true, last) : after;
    }

    private static String selectSql(JdbcPublicationTable table, boolean emptyFamily, boolean journal, boolean started) {
        String renewal = journal ? "COALESCE(j.renew_validity,0)" : "0";
        String journalJoin = journal ? " LEFT JOIN private_mutation_effect j ON j.artifact=?"
                + " AND j.canonical_row_id=d.id AND j.lifecycle_id=d._lifecycle_id" : "";
        String payload = emptyFamily ? table.selectColumns("d") + ",0,"
                + String.join(",", Collections.nCopies(table.columns().size(), "NULL")) + ",0," + renewal
                : table.selectColumns("d") + ",d.publication_deleted," + table.selectColumns("b") + ",b.publication_present," + renewal;
        String source = emptyFamily ? quote(table.name()) + " d" : quote(table.delta()) + " d LEFT JOIN "
                + quote(table.before()) + " b ON " + table.matchKeys("b", "d");
        // Business columns must begin with a letter; the native catalog reserves
        // _rowid_, so a public column named rowid cannot shadow this cursor.
        return "SELECT d._rowid_," + payload + " FROM " + source + journalJoin
                + (started ? " WHERE d._rowid_>?" : "") + " ORDER BY d._rowid_ LIMIT 256";
    }

    private static boolean emptyFamily(Connection connection, JdbcPublicationTable table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM pragma_table_info(?) HAVING COUNT(*)=1"
                + " AND SUM(name='publication_empty')=1")) {
            statement.setString(1, table.delta());
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static void emit(JdbcPublicationTable table, ResultSet rows, EffectConsumer consumer)
            throws SQLException {
        int size = table.columns().size();
        List<Object> values = values(rows, 2, size);
        boolean deleted = rows.getInt(size + 2) == 1;
        List<Object> previous = rows.getInt(2 * size + 3) == 1 ? values(rows, size + 3, size) : null;
        boolean renewal = rows.getInt(2 * size + 4) == 1;
        if ((deleted && previous != null) || (!deleted && (!values.equals(previous) || renewal))) {
            consumer.accept(new Effect(table, values, previous, deleted, renewal));
        }
    }

    private static List<Object> values(ResultSet rows, int first, int size) throws SQLException {
        var values = new ArrayList<Object>(size);
        for (int index = 0; index < size; index++) { values.add(rows.getObject(first + index)); }
        return values;
    }

    private static boolean hasJournal(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_schema WHERE name='private_mutation_effect' AND type='table'");
             var rows = statement.executeQuery()) {
            return rows.next();
        }
    }

    @FunctionalInterface interface EffectConsumer { void accept(Effect effect) throws SQLException; }
}
