package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Records canonical source observations with one shared occurrence policy. */
final class JdbcCanonicalSourceRecorder {

    private JdbcCanonicalSourceRecorder() {
    }

    static void record(Connection connection,
                       String artifact,
                       long rowId,
                       String sourceKey,
                       String observedAt) throws SQLException {
        String sql = "INSERT INTO " + quote(artifact + "_sources") + " ("
                + joinedQuoted(List.of("row_id", "source_key", "first_seen_at", "last_seen_at", "occurrences"))
                + ") VALUES (?, ?, ?, ?, 1) ON CONFLICT(" + quote("row_id") + ", " + quote("source_key")
                + ") DO UPDATE SET " + quote("last_seen_at") + " = excluded." + quote("last_seen_at")
                + ", " + quote("occurrences") + " = " + quote("occurrences") + " + 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rowId);
            statement.setString(2, sourceKey);
            statement.setString(3, observedAt);
            statement.setString(4, observedAt);
            statement.executeUpdate();
        }
    }
}
