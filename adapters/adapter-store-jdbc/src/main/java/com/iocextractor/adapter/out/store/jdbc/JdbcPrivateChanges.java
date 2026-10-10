package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

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

    /** Pages cannot materialize a table or fan-in in heap; the caller bounds payload bytes separately. */
    static long read(Connection connection, JdbcPublicationTable table, long after, Consumer<Effect> consumer)
            throws SQLException {
        if (connection.getAutoCommit() || after < 0) {
            throw new IllegalArgumentException("Private effects require a pinned transaction and valid cursor");
        }
        boolean journal = table.columns().contains("_lifecycle_id") && table.columns().contains("row_key")
                && !table.name().endsWith("_history") && hasJournal(connection);
        String renewal = journal ? "COALESCE(j.renew_validity,0)" : "0";
        String journalJoin = journal ? " LEFT JOIN private_mutation_effect j ON j.artifact=?"
                + " AND j.canonical_row_id=d.id AND j.lifecycle_id=d._lifecycle_id" : "";
        String sql = "SELECT d.rowid," + table.selectColumns("d") + ",d.publication_deleted,"
                + table.selectColumns("b") + ",b.publication_present," + renewal + " FROM "
                + quote(table.delta()) + " d LEFT JOIN " + quote(table.before()) + " b ON "
                + table.matchKeys("b", "d") + journalJoin + " WHERE d.rowid>? ORDER BY d.rowid LIMIT 256";
        long last = after;
        try (var statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (journal) { statement.setString(parameter++, table.name()); }
            statement.setLong(parameter, after);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    last = rows.getLong(1);
                    emit(table, rows, consumer);
                }
            }
        }
        return last;
    }

    private static void emit(JdbcPublicationTable table, ResultSet rows, Consumer<Effect> consumer)
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
}
