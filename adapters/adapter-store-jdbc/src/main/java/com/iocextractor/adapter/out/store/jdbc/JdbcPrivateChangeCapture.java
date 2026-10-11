package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;
import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;

/**
 * Private native-table DML capture. Triggers record addresses and typed values,
 * never change mutation policy, public constraints, UPSERT or affected-row counts.
 * Install after selective hydration, before the native mutation transaction.
 */
final class JdbcPrivateChangeCapture {
    private JdbcPrivateChangeCapture() { }

    static void install(Connection connection, List<JdbcPublicationTable> tables) throws SQLException {
        for (var table : tables) { install(connection, table); }
    }

    private static void install(Connection connection, JdbcPublicationTable table) throws SQLException {
        table.createEmptyCopy(connection, table.before());
        execute(connection, "ALTER TABLE " + quote(table.before()) + " ADD COLUMN publication_present INTEGER");
        if (!table.nativeDdl().toUpperCase(java.util.Locale.ROOT).contains("WITHOUT ROWID")
                && table.columns().stream().noneMatch(column -> column.equalsIgnoreCase("rowid")) && empty(connection, table)) {
            installEmptyFamily(connection, table);
            return;
        }
        table.createEmptyCopy(connection, table.delta());
        execute(connection, "ALTER TABLE " + quote(table.delta()) + " ADD COLUMN publication_deleted INTEGER");
        for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
            String value = operation.equals("DELETE") ? "OLD" : "NEW";
            String deleted = operation.equals("DELETE") ? "1" : "0";
            String sql = "CREATE TRIGGER " + quote("publication_capture_" + table.name() + "_" + operation)
                    + " AFTER " + operation + " ON " + quote(table.name()) + " BEGIN "
                    + (operation.equals("INSERT") ? "" : retainBefore(table))
                    + (operation.equals("UPDATE") ? changedKey(table) : "")
                    + "INSERT INTO " + quote(table.delta()) + "(" + joinedQuoted(table.columns())
                    + ",publication_deleted) VALUES(" + table.selectColumns(value) + "," + deleted
                    + ") ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO UPDATE SET "
                    + table.columns().stream().map(column -> quote(column) + "=excluded." + quote(column))
                    .collect(java.util.stream.Collectors.joining(","))
                    + ",publication_deleted=excluded.publication_deleted; END";
            execute(connection, sql);
        }
    }

    private static boolean empty(Connection connection, JdbcPublicationTable table) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT 1 FROM " + quote(table.name()) + " LIMIT 1")) {
            return !rows.next();
        }
    }

    /** An empty pinned family has no before-images; references avoid a second copy of every inserted payload/key. */
    private static void installEmptyFamily(Connection connection, JdbcPublicationTable table) throws SQLException {
        execute(connection, "CREATE TABLE " + quote(table.delta())
                + "(publication_sequence INTEGER PRIMARY KEY AUTOINCREMENT, publication_rowid INTEGER NOT NULL UNIQUE)");
        for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
            String remove = operation.equals("INSERT") ? "" : "DELETE FROM " + quote(table.delta())
                    + " WHERE publication_rowid=OLD.rowid"
                    + (operation.equals("UPDATE") ? " AND OLD.rowid<>NEW.rowid" : "") + "; ";
            String retain = operation.equals("DELETE") ? "" : "INSERT INTO " + quote(table.delta())
                    + "(publication_rowid) VALUES(NEW.rowid) ON CONFLICT(publication_rowid) DO NOTHING; ";
            execute(connection, "CREATE TRIGGER " + quote("publication_capture_" + table.name() + "_" + operation)
                    + " AFTER " + operation + " ON " + quote(table.name()) + " BEGIN " + remove + retain + "END");
        }
    }

    private static String retainBefore(JdbcPublicationTable table) {
        // An existing delta proves this address was already touched. Inserts have
        // no before-image; later updates/deletes must preserve that original absence.
        return "INSERT INTO " + quote(table.before()) + "(" + joinedQuoted(table.columns())
                + ",publication_present) SELECT " + table.selectColumns("OLD") + ",1 WHERE NOT EXISTS(SELECT 1 FROM "
                + quote(table.delta()) + " d WHERE " + table.matchKeys("d", "OLD") + ")"
                + " ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO NOTHING; ";
    }

    private static String changedKey(JdbcPublicationTable table) {
        String changed = "NOT (" + table.matchKeys("OLD", "NEW") + ")";
        return "INSERT INTO " + quote(table.delta()) + "(" + joinedQuoted(table.columns())
                + ",publication_deleted) SELECT " + table.selectColumns("OLD") + ",1 WHERE " + changed
                + " ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO UPDATE SET publication_deleted=1; ";
    }
}
