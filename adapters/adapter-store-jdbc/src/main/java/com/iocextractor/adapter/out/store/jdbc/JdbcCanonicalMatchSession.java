package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalMatchCandidate;
import com.iocextractor.application.artifact.CanonicalMatchPlan;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Thread-confined alias matcher bound to a connection, immutable schema and effective time. */
final class JdbcCanonicalMatchSession implements AutoCloseable {
    private static final int REQUEST_CHUNK = 256;
    private static final int KEY_BATCH = 256;
    private final Connection connection;
    private final String artifact;
    private final long asOf;
    private final JdbcStatementScope statements;
    private final String singletonSql;
    private final String stagedSql;
    private boolean staged;

    JdbcCanonicalMatchSession(Connection connection, DataframeArtifactSchema schema, EffectiveTime asOf) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.artifact = Objects.requireNonNull(schema, "schema").artifactName();
        this.asOf = Objects.requireNonNull(asOf, "asOf").value().toEpochMilli();
        this.statements = new JdbcStatementScope(connection);
        this.singletonSql = """
                SELECT a.canonical_row_id, a.lifecycle_id, c.row_key
                FROM canonical_match_alias a
                CROSS JOIN ${artifact} c
                  ON c.id = a.canonical_row_id AND c._lifecycle_id = a.lifecycle_id
                WHERE a.artifact = ? AND a.definition_id = ?
                  AND a.key_hash = ? AND a.key_canonical = ?
                  AND c._valid_until_epoch_ms > ?
                ORDER BY a.canonical_row_id, a.lifecycle_id
                """.replace("${artifact}", quote(artifact));
        this.stagedSql = """
                SELECT DISTINCT r.request_id, a.canonical_row_id, a.lifecycle_id, c.row_key
                FROM temp.ioc_match_request r
                CROSS JOIN canonical_match_alias a
                  ON a.artifact = ? AND a.definition_id = r.definition_id
                 AND a.key_hash = r.key_hash AND a.key_canonical = r.key_canonical
                CROSS JOIN ${artifact} c
                  ON c.id = a.canonical_row_id AND c._lifecycle_id = a.lifecycle_id
                WHERE c._valid_until_epoch_ms > ?
                ORDER BY r.request_order, a.canonical_row_id, a.lifecycle_id
                """.replace("${artifact}", quote(artifact));
    }

    List<CanonicalMatchPlan> plan(List<CanonicalMatchRequest> requests) throws SQLException {
        statements.requireOpen();
        List<CanonicalMatchRequest> ordered = List.copyOf(Objects.requireNonNull(requests, "requests"));
        if (ordered.size() == 1 && ordered.getFirst().keys().size() == 1) {
            return List.of(singleton(ordered.getFirst()));
        }
        Map<String, List<CanonicalMatchCandidate>> hits = new LinkedHashMap<>();
        ordered.forEach(request -> hits.put(request.requestId(), new ArrayList<>()));
        if (ordered.stream().anyMatch(request -> !request.keys().isEmpty())) {
            ensureStaging();
            for (int start = 0; start < ordered.size(); start += REQUEST_CHUNK) {
                stage(ordered.subList(start, Math.min(ordered.size(), start + REQUEST_CHUNK)));
                readStaged(hits);
            }
        }
        return ordered.stream().map(request -> CanonicalMatchPlan.from(
                request.requestId(), hits.get(request.requestId()))).toList();
    }

    private CanonicalMatchPlan singleton(CanonicalMatchRequest request) throws SQLException {
        var hits = new ArrayList<CanonicalMatchCandidate>();
        try (var lease = statements.borrow(singletonSql)) {
            PreparedStatement statement = lease.statement();
            CanonicalKeyMaterial key = request.keys().getFirst();
            statement.setString(1, artifact);
            statement.setString(2, key.definitionId());
            statement.setString(3, key.keyHash());
            statement.setString(4, key.keyCanonical());
            statement.setLong(5, asOf);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    hits.add(candidate(rows));
                }
            }
        }
        return CanonicalMatchPlan.from(request.requestId(), hits);
    }

    private void ensureStaging() throws SQLException {
        if (!staged) {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TEMP TABLE ioc_match_request (
                            request_order INTEGER NOT NULL, request_id TEXT NOT NULL,
                            definition_id TEXT NOT NULL, key_hash TEXT NOT NULL, key_canonical TEXT NOT NULL,
                            PRIMARY KEY (request_id, definition_id, key_hash, key_canonical))
                        """);
            }
            staged = true;
        }
    }

    private void stage(List<CanonicalMatchRequest> requests) throws SQLException {
        try (var clear = statements.borrow("DELETE FROM temp.ioc_match_request")) {
            clear.statement().executeUpdate();
        }
        try (var lease = statements.borrow("""
                INSERT OR IGNORE INTO temp.ioc_match_request(
                    request_order, request_id, definition_id, key_hash, key_canonical)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            PreparedStatement statement = lease.statement();
            int queued = 0;
            for (int index = 0; index < requests.size(); index++) {
                CanonicalMatchRequest request = requests.get(index);
                for (CanonicalKeyMaterial key : request.keys()) {
                    statement.setInt(1, index);
                    statement.setString(2, request.requestId());
                    statement.setString(3, key.definitionId());
                    statement.setString(4, key.keyHash());
                    statement.setString(5, key.keyCanonical());
                    statement.addBatch();
                    if (++queued == KEY_BATCH) {
                        statement.executeBatch();
                        statement.clearBatch();
                        queued = 0;
                    }
                }
            }
            if (queued > 0) {
                statement.executeBatch();
            }
        }
    }

    private void readStaged(Map<String, List<CanonicalMatchCandidate>> hits) throws SQLException {
        try (var lease = statements.borrow(stagedSql)) {
            PreparedStatement statement = lease.statement();
            statement.setString(1, artifact);
            statement.setLong(2, asOf);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    hits.get(rows.getString("request_id")).add(candidate(rows));
                }
            }
        }
    }

    private CanonicalMatchCandidate candidate(ResultSet rows) throws SQLException {
        return new CanonicalMatchCandidate(rows.getLong("canonical_row_id"), rows.getLong("lifecycle_id"),
                new ArtifactRowKey(rows.getString("row_key")));
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        try {
            statements.close();
        } catch (SQLException closingFailure) {
            failure = closingFailure;
        }
        if (staged) {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS temp.ioc_match_request");
                staged = false;
            } catch (SQLException closingFailure) {
                if (failure == null) {
                    failure = closingFailure;
                } else {
                    failure.addSuppressed(closingFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
