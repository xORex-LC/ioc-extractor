package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Bounds the committed-base cache and disk-backed requests without changing pooled connection policy. */
final class JdbcPublicationReadLimits implements AutoCloseable {
    private final Connection connection;
    private final long cache;
    private final long tempCache;
    private final long tempStore;
    private final long tempPages;
    JdbcPublicationReadLimits(Connection connection, DocumentPreparationLimits limits) throws SQLException {
        this.connection = connection;
        cache = read("cache_size"); tempStore = read("temp_store"); tempCache = read("temp.cache_size"); tempPages = read("temp.max_page_count");
        try {
            write("cache_size", -limits.cacheKiB()); write("temp_store", 1); write("temp.cache_size", -limits.cacheKiB());
            write("temp.max_page_count", Math.max(16, limits.workspaceBytes() / (4 * 4096)));
        } catch (SQLException failure) {
            try { close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
    private long read(String name) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + name)) {
            if (!rows.next()) { throw new SQLException("Missing SQLite request bound: " + name); }
            return rows.getLong(1);
        }
    }
    private void write(String name, long value) throws SQLException {
        JdbcPublicationTable.execute(connection, "PRAGMA " + name + "=" + value);
    }
    public void close() throws SQLException {
        // SQLite JDBC rollback starts another transaction while auto-commit remains disabled.
        if (!connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); }
        write("cache_size", cache); write("temp.max_page_count", tempPages); write("temp.cache_size", tempCache); write("temp_store", tempStore);
    }
}
