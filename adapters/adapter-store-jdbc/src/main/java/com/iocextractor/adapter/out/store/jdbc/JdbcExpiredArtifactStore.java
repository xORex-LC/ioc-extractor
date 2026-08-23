package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleDeadline;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** SQLite indexed deadline lookup and bounded archive/delete transaction. */
public final class JdbcExpiredArtifactStore implements ExpiredArtifactStore {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final JdbcLifecycleArchive lifecycleArchive;
    private final JdbcLifecycleTransactionObserver transactionObserver;

    /** Creates the expiration store over the configured artifact catalog. */
    public JdbcExpiredArtifactStore(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        this(dataSource, schemas, JdbcLifecycleTransactionObserver.NOOP);
    }

    JdbcExpiredArtifactStore(DataSource dataSource,
                             List<DataframeArtifactSchema> schemas,
                             JdbcLifecycleTransactionObserver transactionObserver) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.lifecycleArchive = new JdbcLifecycleArchive();
        this.transactionObserver = Objects.requireNonNull(transactionObserver, "transactionObserver");
    }

    @Override
    public Optional<LifecycleDeadline> nearestDeadline() {
        try (Connection connection = dataSource.getConnection()) {
            if (JdbcLifecycleTransactions.readActivationState(connection) != LifecycleActivationState.ACTIVE) {
                throw new IocExtractorException("Canonical lifecycle is not active");
            }
            Optional<Long> nearest = Optional.empty();
            for (String artifact : schemas.keySet()) {
                Optional<Long> candidate = nearestDeadline(connection, artifact);
                if (candidate.isPresent() && (nearest.isEmpty()
                        || candidate.orElseThrow() < nearest.orElseThrow())) {
                    nearest = candidate;
                }
            }
            return nearest.map(value -> new LifecycleDeadline(Instant.ofEpochMilli(value)));
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException("Failed to read the nearest lifecycle deadline", e);
        }
    }

    @Override
    public ExpiryBatchResult expireDue(String artifactName,
                                       EffectiveTime cycleAsOf,
                                       int batchSize) {
        Objects.requireNonNull(cycleAsOf, "cycleAsOf");
        DataframeArtifactSchema schema = schema(artifactName);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Expiry batch size must be positive");
        }
        try (Connection connection = dataSource.getConnection()) {
            return expireDue(connection, schema, cycleAsOf, batchSize);
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException("Failed to expire JDBC artifact rows: " + artifactName, e);
        }
    }

    private ExpiryBatchResult expireDue(Connection connection,
                                        DataframeArtifactSchema schema,
                                        EffectiveTime cycleAsOf,
                                        int batchSize) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            JdbcLifecycleTransactions.acquireActiveWriteOwnership(
                    connection,
                    schema.artifactName(),
                    JdbcLifecycleTransactionObserver.Operation.EXPIRE,
                    transactionObserver);
            List<Long> dueRows = selectDueRows(connection, schema.artifactName(), cycleAsOf, batchSize);
            for (long rowId : dueRows) {
                lifecycleArchive.archiveAndDelete(connection, schema, rowId, cycleAsOf);
            }
            ProjectionGeneration generation = dueRows.isEmpty()
                    ? currentGeneration(connection, schema.artifactName())
                    : advanceGeneration(connection, schema.artifactName(), cycleAsOf);
            boolean moreDue = hasDueRows(connection, schema.artifactName(), cycleAsOf);
            long revision = currentRevision(connection, schema.artifactName());
            connection.commit();
            return new ExpiryBatchResult(
                    schema.artifactName(), cycleAsOf, dueRows.size(), moreDue, revision, generation);
        } catch (SQLException | RuntimeException e) {
            failure = e;
            JdbcLifecycleTransactions.rollback(connection, e);
            throw e;
        } finally {
            JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
        }
    }

    private Optional<Long> nearestDeadline(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT MIN(" + quote("_valid_until_epoch_ms") + ") FROM " + quote(artifact))) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long value = resultSet.getLong(1);
                return resultSet.wasNull() ? Optional.empty() : Optional.of(value);
            }
        }
    }

    private List<Long> selectDueRows(Connection connection,
                                     String artifact,
                                     EffectiveTime asOf,
                                     int batchSize) throws SQLException {
        String sql = "SELECT " + quote("id") + " FROM " + quote(artifact)
                + " WHERE " + quote("_valid_until_epoch_ms") + " <= ?"
                + " ORDER BY " + quote("_valid_until_epoch_ms") + ", " + quote("_lifecycle_id")
                + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf.value().toEpochMilli());
            statement.setInt(2, batchSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Long> rows = new ArrayList<>(batchSize);
                while (resultSet.next()) {
                    rows.add(resultSet.getLong(1));
                }
                return rows;
            }
        }
    }

    private boolean hasDueRows(Connection connection, String artifact, EffectiveTime asOf) throws SQLException {
        String sql = "SELECT 1 FROM " + quote(artifact) + " WHERE "
                + quote("_valid_until_epoch_ms") + " <= ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf.value().toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private ProjectionGeneration advanceGeneration(Connection connection,
                                                   String artifact,
                                                   EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO artifact_projection_state(
                    artifact, required_generation, projected_generation, requested_at_ms)
                VALUES (?, 1, 0, ?)
                ON CONFLICT(artifact) DO UPDATE SET
                    required_generation = artifact_projection_state.required_generation + 1,
                    requested_at_ms = excluded.requested_at_ms,
                    last_error_code = NULL
                """)) {
            statement.setString(1, artifact);
            statement.setLong(2, asOf.value().toEpochMilli());
            statement.executeUpdate();
        }
        return currentGeneration(connection, artifact);
    }

    private ProjectionGeneration currentGeneration(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT required_generation FROM artifact_projection_state WHERE artifact = ?")) {
            statement.setString(1, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                return new ProjectionGeneration(resultSet.next() ? resultSet.getLong(1) : 0L);
            }
        }
    }

    private long currentRevision(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM artifact_revision WHERE artifact = ?")) {
            statement.setString(1, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private DataframeArtifactSchema schema(String artifactName) {
        DataframeArtifactSchema schema = schemas.get(artifactName);
        if (schema == null) {
            throw new IocExtractorException("Unknown dataframe artifact: " + artifactName);
        }
        return schema;
    }

    private Map<String, DataframeArtifactSchema> schemasByName(List<DataframeArtifactSchema> source) {
        Objects.requireNonNull(source, "schemas");
        Map<String, DataframeArtifactSchema> result = new LinkedHashMap<>();
        source.stream()
                .sorted(Comparator.comparing(DataframeArtifactSchema::artifactName))
                .forEach(schema -> {
                    if (result.put(schema.artifactName(), schema) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate dataframe artifact schema: " + schema.artifactName());
                    }
                });
        return Map.copyOf(result);
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }
}
