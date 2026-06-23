package com.iocextractor.adapter.out.store.jdbc;

/**
 * One monotonic SQLite schema migration backed by SQL text.
 */
public record SqliteSchemaMigration(int version, String name, String sql) {

    public SqliteSchemaMigration {
        if (version < 1) {
            throw new IllegalArgumentException("Schema migration version must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Schema migration name is required");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Schema migration SQL is required");
        }
    }
}
