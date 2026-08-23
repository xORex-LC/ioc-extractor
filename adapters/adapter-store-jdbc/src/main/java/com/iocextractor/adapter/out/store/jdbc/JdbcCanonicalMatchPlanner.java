package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalMatchCandidate;
import com.iocextractor.application.artifact.CanonicalMatchPlan;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.port.out.artifact.CanonicalMatchPlanner;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SQLite set-based active alias matcher. */
public final class JdbcCanonicalMatchPlanner implements CanonicalMatchPlanner {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;

    /** Creates a planner for the configured dataframe artifacts. */
    public JdbcCanonicalMatchPlanner(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        var indexed = new LinkedHashMap<String, DataframeArtifactSchema>();
        for (DataframeArtifactSchema schema : List.copyOf(Objects.requireNonNull(schemas, "schemas"))) {
            if (indexed.putIfAbsent(schema.artifactName(), schema) != null) {
                throw new IllegalArgumentException("Duplicate dataframe schema: " + schema.artifactName());
            }
        }
        this.schemas = Map.copyOf(indexed);
    }

    @Override
    public List<CanonicalMatchPlan> plan(String artifactName,
                                         EffectiveTime asOf,
                                         List<CanonicalMatchRequest> requests) {
        DataframeArtifactSchema schema = schemas.get(artifactName);
        if (schema == null) {
            throw new IocExtractorException("Unknown dataframe artifact: " + artifactName);
        }
        try (Connection connection = dataSource.getConnection()) {
            return plan(connection, schema, asOf, requests);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to plan canonical matches for " + artifactName, e);
        }
    }

    List<CanonicalMatchPlan> plan(Connection connection,
                                  DataframeArtifactSchema schema,
                                  EffectiveTime asOf,
                                  List<CanonicalMatchRequest> requests) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(asOf, "asOf");
        List<CanonicalMatchRequest> ordered = List.copyOf(Objects.requireNonNull(requests, "requests"));
        createRequestTable(connection);
        stageRequests(connection, ordered);

        Map<String, List<CanonicalMatchCandidate>> hits = new LinkedHashMap<>();
        ordered.forEach(request -> hits.put(request.requestId(), new ArrayList<>()));
        String sql = """
                SELECT DISTINCT r.request_id, a.canonical_row_id, a.lifecycle_id, c.row_key
                FROM temp.ioc_match_request r
                JOIN canonical_match_alias a
                  ON a.artifact = ?
                 AND a.definition_id = r.definition_id
                 AND a.key_hash = r.key_hash
                 AND a.key_canonical = r.key_canonical
                JOIN ${artifact} c
                  ON c.id = a.canonical_row_id
                 AND c._lifecycle_id = a.lifecycle_id
                WHERE c._valid_until_epoch_ms > ?
                ORDER BY r.request_order, a.canonical_row_id, a.lifecycle_id
                """.replace("${artifact}", quote(schema.artifactName()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema.artifactName());
            statement.setLong(2, asOf.value().toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    List<CanonicalMatchCandidate> candidates = hits.get(resultSet.getString("request_id"));
                    if (candidates != null) {
                        candidates.add(new CanonicalMatchCandidate(
                                resultSet.getLong("canonical_row_id"),
                                resultSet.getLong("lifecycle_id"),
                                new ArtifactRowKey(resultSet.getString("row_key"))));
                    }
                }
            }
        }
        return ordered.stream()
                .map(request -> CanonicalMatchPlan.from(request.requestId(), hits.get(request.requestId())))
                .toList();
    }

    private void createRequestTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS temp.ioc_match_request");
            statement.execute("""
                    CREATE TEMP TABLE ioc_match_request (
                        request_order INTEGER NOT NULL,
                        request_id TEXT NOT NULL,
                        definition_id TEXT NOT NULL,
                        key_hash TEXT NOT NULL,
                        key_canonical TEXT NOT NULL,
                        PRIMARY KEY (request_id, definition_id, key_hash, key_canonical)
                    )
                    """);
        }
    }

    private void stageRequests(Connection connection, List<CanonicalMatchRequest> requests) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO temp.ioc_match_request(
                    request_order, request_id, definition_id, key_hash, key_canonical)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++) {
                CanonicalMatchRequest request = requests.get(requestIndex);
                for (CanonicalKeyMaterial key : request.keys()) {
                    statement.setInt(1, requestIndex);
                    statement.setString(2, request.requestId());
                    statement.setString(3, key.definitionId());
                    statement.setString(4, key.keyHash());
                    statement.setString(5, key.keyCanonical());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }
}
