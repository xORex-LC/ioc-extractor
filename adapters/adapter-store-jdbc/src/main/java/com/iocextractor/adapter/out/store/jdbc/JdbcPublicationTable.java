package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Immutable physical shape; publication never interprets business field values. */
record JdbcPublicationTable(String name, List<String> columns, List<String> keys, String nativeDdl) {
    JdbcPublicationTable {
        columns = List.copyOf(columns);
        keys = List.copyOf(keys);
        if (columns.isEmpty() || keys.isEmpty() || !columns.containsAll(keys)) {
            throw new IllegalArgumentException("A typed publication table requires a primary key");
        }
    }

    static JdbcPublicationTable inspect(Connection connection, String name) throws SQLException {
        var columns = new ArrayList<String>();
        var keys = new TreeMap<Integer, String>();
        try (var statement = connection.prepareStatement("SELECT name,pk FROM pragma_table_info(?)")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString("name"));
                    if (rows.getInt("pk") > 0) { keys.put(rows.getInt("pk"), rows.getString("name")); }
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE type='table' AND name=?")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Missing native publication table: " + name); }
                return new JdbcPublicationTable(name, columns, new ArrayList<>(keys.values()), rows.getString(1));
            }
        }
    }

    String selectColumns(String alias) {
        return columns.stream().map(column -> alias + "." + quote(column))
                .collect(java.util.stream.Collectors.joining(","));
    }

    String matchKeys(String left, String right) {
        return keys.stream().map(key -> left + "." + quote(key) + " IS " + right + "." + quote(key))
                .collect(java.util.stream.Collectors.joining(" AND "));
    }

    String delta() { return "publication_delta_" + name; }
    String before() { return "publication_before_" + name; }
    String versions() { return "publication_version_" + name; }

    void createEmptyCopy(Connection connection, String copy) throws SQLException {
        execute(connection, "CREATE TABLE " + quote(copy) + " AS SELECT " + joinedQuoted(columns)
                + " FROM " + quote(name) + " WHERE 0");
        execute(connection, "CREATE UNIQUE INDEX " + quote(copy + "_key") + " ON " + quote(copy)
                + "(" + joinedQuoted(keys) + ")");
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
