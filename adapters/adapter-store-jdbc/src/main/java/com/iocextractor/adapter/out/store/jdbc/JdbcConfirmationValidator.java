package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import java.sql.Connection;
import java.sql.SQLException;

/** Disk-backed duplicate validation before any canonical ID reservation. */
final class JdbcConfirmationValidator {
    private JdbcConfirmationValidator() { }

    static void validate(Connection connection, CanonicalArtifactConfirmation confirmation, boolean hasPublicId)
            throws SQLException {
        try (var ddl = connection.createStatement()) {
            ddl.execute("CREATE TEMP TABLE cap_confirmation_keys(row_key TEXT PRIMARY KEY) WITHOUT ROWID");
            ddl.execute("PRAGMA temp.cache_size=-64");
            // A malformed external streaming command cannot fill the system temporary disk.
            ddl.execute("PRAGMA temp.max_page_count=65536");
        }
        try (var keys = connection.prepareStatement("INSERT INTO cap_confirmation_keys(row_key) VALUES (?)");
             var cursor = JdbcRowSources.onConnection(confirmation.records(), connection).open()) {
            int count = 0;
            while (cursor.next()) {
                var record = cursor.value();
                var row = record.preparedRow();
                boolean validId = hasPublicId ? row.idColumn().filter("id"::equals).isPresent() : row.idColumn().isEmpty();
                if (!validId) { throw new IllegalArgumentException("Prepared public-id slot does not match artifact schema: " + confirmation.artifactName()); }
                if (row.idColumn().isPresent()) {
                    String supplied = row.template().value(row.idColumn().orElseThrow());
                    if (supplied != null && !supplied.isBlank()) { throw new IllegalArgumentException("Service-owned public id must remain deferred"); }
                }
                keys.setString(1, record.rowKey().value());
                try { keys.executeUpdate(); }
                catch (SQLException failure) {
                    if (failure.getErrorCode() == 19) {
                        throw new IllegalArgumentException("Canonical confirmation contains duplicate row key: " + record.rowKey().value(), failure);
                    }
                    throw failure;
                }
                count++;
            }
            if (count != confirmation.records().size()) { throw new IllegalArgumentException("Canonical confirmation row count mismatch"); }
        } finally {
            try (var ddl = connection.createStatement()) { ddl.execute("DROP TABLE cap_confirmation_keys"); }
        }
    }
}
