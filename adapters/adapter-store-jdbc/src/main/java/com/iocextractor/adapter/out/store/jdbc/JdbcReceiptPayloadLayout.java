package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** Persisted candidate capability for the audited immutable ordinary-receipt payload families. */
enum JdbcReceiptPayloadLayout {
    VERSIONED, IMMUTABLE;

    boolean contains(JdbcPublicationTable table, Set<String> artifacts) {
        String name = table.name();
        return this == IMMUTABLE && (name.equals("confirmation_receipt_field_position")
                || (name.endsWith("_receipt_rows") && artifacts.contains(name.substring(0, name.length() - "_receipt_rows".length()))));
    }

    String pins(String configuration) {
        return this == VERSIONED ? configuration : configuration + ":immutable-receipt-v1";
    }

    void persist(Connection connection) throws SQLException {
        JdbcPublicationTable.execute(connection, "CREATE TABLE publication_receipt_layout("
                + "singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),format TEXT NOT NULL)");
        try (var statement = connection.prepareStatement("INSERT INTO publication_receipt_layout VALUES(1,?)")) {
            statement.setString(1, name()); statement.executeUpdate();
        }
    }

    static JdbcReceiptPayloadLayout load(Connection connection) throws SQLException {
        boolean persisted;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 1 FROM sqlite_schema WHERE type='table' AND name='publication_receipt_layout'")) {
            persisted = rows.next();
        }
        if (!persisted) { return VERSIONED; }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT format FROM publication_receipt_layout WHERE singleton_id=1")) {
            if (!rows.next()) { throw new SQLException("Missing receipt payload layout"); }
            try { return valueOf(rows.getString(1)); }
            catch (IllegalArgumentException failure) { throw new SQLException("Unsupported receipt payload layout", failure); }
        }
    }
}
