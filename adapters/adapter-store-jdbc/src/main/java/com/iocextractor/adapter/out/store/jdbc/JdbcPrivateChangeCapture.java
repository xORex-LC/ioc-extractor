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
        table.createEmptyCopy(connection, table.delta());
        execute(connection, "ALTER TABLE " + quote(table.delta()) + " ADD COLUMN publication_deleted INTEGER");
        for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
            String value = operation.equals("DELETE") ? "OLD" : "NEW";
            String before = operation.equals("INSERT") ? "NEW" : "OLD";
            String present = operation.equals("INSERT") ? "0" : "1";
            String deleted = operation.equals("DELETE") ? "1" : "0";
            String sql = "CREATE TRIGGER " + quote("publication_capture_" + table.name() + "_" + operation)
                    + " AFTER " + operation + " ON " + quote(table.name()) + " BEGIN "
                    + (operation.equals("UPDATE") ? changedKey(table) : "")
                    + "INSERT INTO " + quote(table.before()) + "(" + joinedQuoted(table.columns())
                    + ",publication_present) VALUES(" + table.selectColumns(before) + "," + present
                    + ") ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO NOTHING; "
                    + "INSERT INTO " + quote(table.delta()) + "(" + joinedQuoted(table.columns())
                    + ",publication_deleted) VALUES(" + table.selectColumns(value) + "," + deleted
                    + ") ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO UPDATE SET "
                    + table.columns().stream().map(column -> quote(column) + "=excluded." + quote(column))
                    .collect(java.util.stream.Collectors.joining(","))
                    + ",publication_deleted=excluded.publication_deleted; END";
            execute(connection, sql);
        }
    }

    private static String changedKey(JdbcPublicationTable table) {
        String changed = "NOT (" + table.matchKeys("OLD", "NEW") + ")";
        return "INSERT INTO " + quote(table.before()) + "(" + joinedQuoted(table.columns())
                + ",publication_present) SELECT " + table.selectColumns("OLD") + ",1 WHERE " + changed
                + " ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO NOTHING; "
                + "INSERT INTO " + quote(table.delta()) + "(" + joinedQuoted(table.columns())
                + ",publication_deleted) SELECT " + table.selectColumns("OLD") + ",1 WHERE " + changed
                + " ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO UPDATE SET publication_deleted=1; "
                + "INSERT INTO " + quote(table.before()) + "(" + joinedQuoted(table.columns())
                + ",publication_present) SELECT " + table.selectColumns("NEW") + ",0 WHERE " + changed
                + " ON CONFLICT(" + joinedQuoted(table.keys()) + ") DO NOTHING; ";
    }
}
