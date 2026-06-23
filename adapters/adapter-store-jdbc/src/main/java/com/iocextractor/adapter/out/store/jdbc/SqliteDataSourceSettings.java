package com.iocextractor.adapter.out.store.jdbc;

/**
 * Minimal datasource settings for a SQLite-backed storage role.
 */
public record SqliteDataSourceSettings(String role,
                                       String jdbcUrl,
                                       String tuning,
                                       int writeMax,
                                       int readMax) {

    public SqliteDataSourceSettings {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("SQLite datasource role is required");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("SQLite JDBC URL is required");
        }
        if (writeMax < 1) {
            throw new IllegalArgumentException("SQLite writeMax must be positive");
        }
        if (readMax < 1) {
            throw new IllegalArgumentException("SQLite readMax must be positive");
        }
    }

    /**
     * Returns the capacity of the current single Hikari pool. Dedicated
     * read/write pools are introduced by bootstrap wiring when JDBC ledger
     * beans are selected; this factory only opens one concrete pool.
     *
     * @return single-pool maximum size
     */
    public int maxPoolSize() {
        return writeMax + readMax;
    }
}
