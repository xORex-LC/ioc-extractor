package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;
import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;

/** Disk-backed requests and selective row/lifecycle addresses on one committed read snapshot. */
final class JdbcPublicationSelection implements AutoCloseable {
    private final Connection connection;
    private final JdbcStatementScope statements;

    JdbcPublicationSelection(Connection connection) throws SQLException {
        this.connection = connection;
        this.statements = new JdbcStatementScope(connection);
        execute(connection, "CREATE TEMP TABLE publication_record_request(artifact TEXT,row_key TEXT,"
                + "PRIMARY KEY(artifact,row_key)) WITHOUT ROWID");
        execute(connection, "CREATE TEMP TABLE publication_match_request(artifact TEXT,definition_id TEXT,"
                + "key_hash TEXT,key_canonical TEXT,PRIMARY KEY(artifact,definition_id,key_hash,key_canonical)) WITHOUT ROWID");
        execute(connection, "CREATE TEMP TABLE publication_selected_row(artifact TEXT,row_id INTEGER,lifecycle_id INTEGER,"
                + "PRIMARY KEY(artifact,row_id)) WITHOUT ROWID");
    }

    void request(String artifact, String rowKey, List<CanonicalKeyMaterial> keys) throws SQLException {
        try (var lease = statements.borrow("INSERT OR IGNORE INTO temp.publication_record_request VALUES(?,?)")) {
            lease.statement().setString(1, artifact); lease.statement().setString(2, rowKey); lease.statement().executeUpdate();
        }
        for (var key : keys) { match(artifact, key); }
    }

    void match(String artifact, CanonicalKeyMaterial key) throws SQLException {
        try (var lease = statements.borrow("INSERT OR IGNORE INTO temp.publication_match_request VALUES(?,?,?,?)")) {
            PreparedStatement statement = lease.statement();
            statement.setString(1, artifact); statement.setString(2, key.definitionId());
            statement.setString(3, key.keyHash()); statement.setString(4, key.keyCanonical()); statement.executeUpdate();
        }
    }

    void select(String artifact, long preparedUtc) throws SQLException {
        String exact = "INSERT OR IGNORE INTO temp.publication_selected_row SELECT ?,b.id,b._lifecycle_id"
                + " FROM temp.publication_record_request r CROSS JOIN " + quote(artifact)
                + " b ON b.row_key=r.row_key WHERE r.artifact=?";
        try (var statement = connection.prepareStatement(exact)) {
            statement.setString(1, artifact); statement.setString(2, artifact); statement.executeUpdate();
        }
        String alternatives = "INSERT OR IGNORE INTO temp.publication_selected_row SELECT ?,b.id,b._lifecycle_id"
                + " FROM temp.publication_match_request r CROSS JOIN canonical_match_alias a"
                + " ON a.artifact=r.artifact AND a.definition_id=r.definition_id AND a.key_hash=r.key_hash"
                + " AND a.key_canonical=r.key_canonical CROSS JOIN " + quote(artifact)
                + " b ON b.id=a.canonical_row_id AND b._lifecycle_id=a.lifecycle_id"
                + " WHERE r.artifact=? AND b._valid_until_epoch_ms>?";
        try (var statement = connection.prepareStatement(alternatives)) {
            statement.setString(1, artifact); statement.setString(2, artifact); statement.setLong(3, preparedUtc);
            statement.executeUpdate();
        }
    }

    long minimumDeadline(java.util.Set<String> artifacts, long asOf) throws SQLException {
        long minimum = Long.MAX_VALUE;
        for (String artifact : artifacts) {
            String sql = "SELECT MIN(b._valid_until_epoch_ms) FROM temp.publication_selected_row r CROSS JOIN "
                    + quote(artifact) + " b ON b.id=r.row_id AND b._lifecycle_id=r.lifecycle_id"
                    + " WHERE r.artifact=? AND b._valid_until_epoch_ms>?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, artifact); statement.setLong(2, asOf);
                try (var rows = statement.executeQuery()) {
                    if (rows.next() && rows.getObject(1) != null) { minimum = Math.min(minimum, rows.getLong(1)); }
                }
            }
        }
        return minimum;
    }

    @Override public void close() throws SQLException { statements.close(); }
}
