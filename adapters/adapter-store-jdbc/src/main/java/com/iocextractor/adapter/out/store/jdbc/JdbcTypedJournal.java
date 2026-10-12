package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Isolated transfer evidence choice; typed versions remain the complete business facts. */
enum JdbcTypedJournal {
    ROW_POINTER, CHUNK_POINTER;

    void persist(Connection connection) throws SQLException {
        JdbcPublicationTable.execute(connection, "CREATE TABLE publication_journal_format("
                + "singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),format TEXT NOT NULL)");
        try (var statement = connection.prepareStatement("INSERT INTO publication_journal_format VALUES(1,?)")) {
            statement.setString(1, name()); statement.executeUpdate();
        }
    }

    static JdbcTypedJournal load(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 1 FROM sqlite_schema WHERE type='table' AND name='publication_journal_format'")) {
            if (!rows.next()) { return ROW_POINTER; } // Existing candidate layout.
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT format FROM publication_journal_format WHERE singleton_id=1")) {
            if (!rows.next()) { throw new SQLException("Missing typed journal format"); }
            try { return valueOf(rows.getString(1)); }
            catch (IllegalArgumentException failure) { throw new SQLException("Unsupported typed journal format", failure); }
        }
    }

    String pins(String configuration) {
        return this == ROW_POINTER ? configuration : configuration + ":chunk-journal-v1";
    }
}
