package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import java.sql.Connection;
import java.sql.SQLException;

/** Disk-backed duplicate validation before any canonical ID reservation. */
final class JdbcConfirmationValidator {
    private static final int SQLITE_CONSTRAINT_CODE = 19;
    // Pages, not bytes: 65,536 pages are 256 MiB at the usual 4 KiB SQLite page size.
    private static final long MAXIMUM_TEMP_PAGES = 65_536;
    private static final int TEMP_CACHE_KIB = 64;

    private JdbcConfirmationValidator() { }

    static void validate(Connection connection, CanonicalArtifactConfirmation confirmation, boolean hasPublicId)
            throws SQLException {
        try (var ignored = new TemporaryKeys(connection);
             var keys = connection.prepareStatement("INSERT INTO temp.cap_confirmation_keys(row_key) VALUES (?)");
             var cursor = JdbcRowSources.onConnection(confirmation.records(), connection).open()) {
            int count = 0;
            while (cursor.next()) {
                var record = cursor.value();
                validateDeferredId(record.preparedRow(), hasPublicId, confirmation.artifactName());
                keys.setString(1, record.rowKey().value());
                try { keys.executeUpdate(); }
                catch (SQLException failure) {
                    if (failure.getErrorCode() == SQLITE_CONSTRAINT_CODE) {
                        throw new IllegalArgumentException("Canonical confirmation contains duplicate row key: " + record.rowKey().value(), failure);
                    }
                    throw failure;
                }
                count++;
            }
            if (count != confirmation.records().size()) { throw new IllegalArgumentException("Canonical confirmation row count mismatch"); }
        }
    }

    private static void validateDeferredId(PreparedArtifactRow row, boolean hasPublicId, String artifact) {
        boolean valid = hasPublicId ? row.idColumn().filter("id"::equals).isPresent() : row.idColumn().isEmpty();
        if (!valid) { throw new IllegalArgumentException("Prepared public-id slot does not match artifact schema: " + artifact); }
        if (row.idColumn().isPresent()) {
            String supplied = row.template().value(row.idColumn().orElseThrow());
            if (supplied != null && !supplied.isBlank()) { throw new IllegalArgumentException("Service-owned public id must remain deferred"); }
        }
    }

    /** Owns the TEMP table without owning the caller's connection or transaction. */
    private static final class TemporaryKeys implements AutoCloseable {
        private final Connection connection;

        private TemporaryKeys(Connection connection) throws SQLException {
            this.connection = connection;
            long pageLimit;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA temp.max_page_count")) {
                if (!rows.next()) { throw new SQLException("Missing confirmation TEMP page limit"); }
                pageLimit = Math.min(MAXIMUM_TEMP_PAGES, rows.getLong(1));
            }
            try (var ddl = connection.createStatement()) {
                ddl.execute("CREATE TEMP TABLE temp.cap_confirmation_keys(row_key TEXT PRIMARY KEY) WITHOUT ROWID");
                ddl.execute("PRAGMA temp.cache_size=-" + TEMP_CACHE_KIB);
                // A malformed external streaming command cannot fill the system temporary disk.
                ddl.execute("PRAGMA temp.max_page_count=" + pageLimit);
            }
        }

        @Override
        public void close() throws SQLException {
            try (var ddl = connection.createStatement()) { ddl.execute("DROP TABLE temp.cap_confirmation_keys"); }
        }
    }
}
