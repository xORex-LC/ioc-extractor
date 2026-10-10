package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Optional bounded native/import stage cache; zero preserves the existing runtime policy. */
final class JdbcImportStageReadPolicy {
    private JdbcImportStageReadPolicy() { }
    static void configure(Connection connection, String schema, int cacheKiB) throws SQLException {
        if (cacheKiB > 0) {
            JdbcPublicationTable.execute(connection, "PRAGMA " + JdbcSql.quote(schema) + ".cache_size=-" + cacheKiB);
        }
    }
}
