package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.DocumentArtifactCommit;
import com.iocextractor.application.artifact.DocumentWriteIdentity;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Shared SQL for canonical observation identity and per-artifact transaction evidence. */
final class JdbcDocumentCommitMarkers {

    private JdbcDocumentCommitMarkers() {
    }

    static Map<String, DocumentArtifactCommit> find(Connection connection, DocumentWriteIdentity identity)
            throws SQLException {
        var commits = new LinkedHashMap<String, DocumentArtifactCommit>();
        try (var statement = connection.prepareStatement("""
                SELECT c.artifact, c.inserted, c.restarted, c.updated, c.metadata_only,
                       c.artifact_revision, c.projection_generation,
                       o.source_key, o.document_plan_id, o.document_lifecycle
                FROM canonical_observation o
                LEFT JOIN canonical_observation_commit c ON o.observation_id = c.observation_id
                WHERE o.observation_id = ?
                """)) {
            statement.setString(1, identity.registration().observationId().value());
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    verifyIdentity(result, identity.sourceKey(), identity);
                    String artifact = result.getString("artifact");
                    if (artifact != null) {
                        commits.put(artifact, new DocumentArtifactCommit(
                                Math.addExact(result.getInt("inserted"), result.getInt("restarted")),
                                result.getInt("updated"), result.getInt("metadata_only"),
                                result.getLong("artifact_revision"),
                                new ProjectionGeneration(result.getLong("projection_generation"))));
                    }
                }
            }
        }
        return Map.copyOf(commits);
    }

    static void verifyIdentity(ResultSet result, String sourceKey, DocumentWriteIdentity document)
            throws SQLException {
        if (!sourceKey.equals(result.getString("source_key"))) {
            throw new IocExtractorException("Observation id was already used for another source");
        }
        String expectedPlan = document == null ? null : document.planId();
        String expectedMode = document == null ? null : document.lifecycleActive() ? "1" : "0";
        if (!Objects.equals(expectedPlan, result.getString("document_plan_id"))
                || !Objects.equals(expectedMode, result.getString("document_lifecycle"))) {
            throw new IocExtractorException("Canonical observation plan or lifecycle mode differs");
        }
    }

    static void ensureWritableObservation(Connection connection, ObservationId observation, String sourceKey,
                                          long startedAt, DocumentWriteIdentity document) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO canonical_observation(
                    observation_id, source_key, state, started_at_ms, document_plan_id, document_lifecycle)
                VALUES (?, ?, 'OPEN', ?, ?, ?)
                ON CONFLICT(observation_id) DO NOTHING
                """)) {
            statement.setString(1, observation.value());
            statement.setString(2, sourceKey);
            statement.setLong(3, startedAt);
            statement.setString(4, document == null ? null : document.planId());
            statement.setObject(5, document == null ? null : document.lifecycleActive() ? 1 : 0);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                SELECT source_key, state, document_plan_id, document_lifecycle
                FROM canonical_observation
                WHERE observation_id = ?
                """)) {
            statement.setString(1, observation.value());
            try (var result = statement.executeQuery()) {
                if (!result.next() || !"OPEN".equals(result.getString("state"))) {
                    throw new IocExtractorException("Canonical observation identity is not writable");
                }
                verifyIdentity(result, sourceKey, document);
            }
        }
    }

    static void insertCompatibility(Connection connection, DocumentWriteIdentity document,
                                   String artifact, long now, DocumentArtifactCommit outcome)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO canonical_observation_commit(
                    observation_id, artifact, committed_at_ms, effective_as_of_ms,
                    inserted, renewed, restarted, updated, metadata_only, artifact_revision, projection_generation)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)
                """)) {
            statement.setString(1, document.registration().observationId().value());
            statement.setString(2, artifact);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.setInt(5, outcome.inserted());
            statement.setInt(6, outcome.publicRowsUpdated());
            statement.setInt(7, outcome.metadataOnlyRows());
            statement.setLong(8, outcome.revision());
            statement.setLong(9, outcome.generation().value());
            statement.executeUpdate();
        }
    }

    static ProjectionGeneration advanceProjectionGeneration(Connection connection, String artifact, long now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO artifact_projection_state(
                    artifact, required_generation, projected_generation, requested_at_ms)
                VALUES (?, 1, 0, ?)
                ON CONFLICT(artifact) DO UPDATE SET
                    required_generation = artifact_projection_state.required_generation + 1,
                    requested_at_ms = excluded.requested_at_ms,
                    last_error_code = NULL
                RETURNING required_generation
                """)) {
            statement.setString(1, artifact);
            statement.setLong(2, now);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IocExtractorException("Projection generation update returned no result");
                }
                return new ProjectionGeneration(result.getLong(1));
            }
        }
    }
}
