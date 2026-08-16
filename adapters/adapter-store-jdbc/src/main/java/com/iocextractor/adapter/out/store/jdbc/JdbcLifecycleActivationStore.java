package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationBatchResult;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleActivationStore;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SQLite resumable batches for the named {@code existing-records: expire} policy. */
public final class JdbcLifecycleActivationStore implements LifecycleActivationStore {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final JdbcLifecycleMetadataInspector metadataInspector;
    private final JdbcLifecycleIdAllocator lifecycleIds;
    private final JdbcLifecycleArchive archive = new JdbcLifecycleArchive();

    public JdbcLifecycleActivationStore(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        Clock allocatorClock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.metadataInspector = new JdbcLifecycleMetadataInspector(dataSource);
        this.lifecycleIds = new JdbcLifecycleIdAllocator(
                dataSource, Objects.requireNonNull(allocatorClock, "allocatorClock"));
        Objects.requireNonNull(schemas, "schemas");
        var byName = new LinkedHashMap<String, DataframeArtifactSchema>();
        schemas.stream().sorted(Comparator.comparing(DataframeArtifactSchema::artifactName))
                .forEach(schema -> {
                    if (byName.put(schema.artifactName(), schema) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate dataframe artifact schema: " + schema.artifactName());
                    }
                });
        this.schemas = Map.copyOf(byName);
    }

    @Override
    public boolean hasLegacyRecords() {
        boolean legacy = false;
        for (String artifact : schemas.keySet()) {
            JdbcLifecycleMetadataInspector.LifecycleMetadataSummary summary =
                    metadataInspector.inspect(artifact);
            if (summary.invalidRows() > 0) {
                throw new IocExtractorException(
                        "Lifecycle metadata is partially populated for artifact: " + artifact);
            }
            legacy |= summary.legacyRows() > 0;
        }
        return legacy;
    }

    @Override
    public LifecycleActivationBatchResult expireLegacyBatch(
            String artifactName, EffectiveTime activationAsOf, int batchSize) {
        DataframeArtifactSchema schema = schema(artifactName);
        JdbcLifecycleMetadataInspector.LifecycleMetadataSummary summary =
                metadataInspector.inspect(artifactName);
        if (summary.invalidRows() > 0) {
            throw new IocExtractorException(
                    "Lifecycle metadata is partially populated for artifact: " + artifactName);
        }
        Objects.requireNonNull(activationAsOf, "activationAsOf");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Activation batch size must be positive");
        }
        LifecycleIdReservation reservation = lifecycleIds.reserve(nextBatchSize(schema, batchSize));
        try (Connection connection = dataSource.getConnection()) {
            return expireBatch(connection, schema, activationAsOf, batchSize, reservation);
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException(
                    "Failed to expire legacy rows for artifact: " + artifactName, e);
        }
    }

    private int nextBatchSize(DataframeArtifactSchema schema, int batchSize) {
        String sql = "SELECT COUNT(*) FROM (SELECT 1 FROM " + quote(schema.artifactName())
                + " WHERE " + quote("_lifecycle_id") + " IS NULL"
                + " AND " + quote("_first_confirmed_at_epoch_ms") + " IS NULL"
                + " AND " + quote("_last_confirmed_at_epoch_ms") + " IS NULL"
                + " AND " + quote("_valid_until_epoch_ms") + " IS NULL LIMIT ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, batchSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException e) {
            throw new IocExtractorException(
                    "Failed to size the next legacy activation batch: " + schema.artifactName(), e);
        }
    }

    private LifecycleActivationBatchResult expireBatch(Connection connection,
                                                        DataframeArtifactSchema schema,
                                                        EffectiveTime activationAsOf,
                                                        int batchSize,
                                                        LifecycleIdReservation reservation)
            throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            JdbcLifecycleTransactions.acquireActivatingWriteOwnership(
                    connection, schema.artifactName());
            ActivationProgress progress = loadOrCreateProgress(
                    connection, schema.artifactName(), activationAsOf);
            if (progress.completed()) {
                connection.commit();
                return new LifecycleActivationBatchResult(schema.artifactName(), 0, false);
            }
            List<Long> rows = selectLegacyRows(
                    connection, schema.artifactName(), progress.afterRowId(), batchSize);
            for (int index = 0; index < rows.size(); index++) {
                archive.archiveLegacyAndDelete(
                        connection, schema, rows.get(index), reservation.idAt(index), activationAsOf);
            }
            long expiredTotal = Math.addExact(progress.expiredCount(), rows.size());
            Long afterRowId = rows.isEmpty() ? progress.afterRowId() : rows.get(rows.size() - 1);
            boolean more = hasLegacyRowsAfter(connection, schema.artifactName(), afterRowId);
            if (progress.expiredCount() == 0 && !rows.isEmpty()) {
                requestProjection(connection, schema.artifactName(), activationAsOf);
            }
            updateProgress(connection, schema.artifactName(), afterRowId, expiredTotal, !more, activationAsOf);
            connection.commit();
            return new LifecycleActivationBatchResult(schema.artifactName(), rows.size(), more);
        } catch (SQLException | RuntimeException e) {
            failure = e;
            JdbcLifecycleTransactions.rollback(connection, e);
            throw e;
        } finally {
            JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
        }
    }

    private ActivationProgress loadOrCreateProgress(Connection connection,
                                                    String artifact,
                                                    EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO lifecycle_activation_progress(
                    artifact, after_row_id, expired_count, completed, updated_at_ms)
                VALUES (?, NULL, 0, 0, ?)
                ON CONFLICT(artifact) DO NOTHING
                """)) {
            statement.setString(1, artifact);
            statement.setLong(2, asOf.value().toEpochMilli());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT after_row_id, expired_count, completed
                FROM lifecycle_activation_progress
                WHERE artifact = ?
                """)) {
            statement.setString(1, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IocExtractorException("Lifecycle activation progress is missing");
                }
                long after = resultSet.getLong("after_row_id");
                Long afterRowId = resultSet.wasNull() ? null : after;
                return new ActivationProgress(
                        afterRowId, resultSet.getLong("expired_count"),
                        resultSet.getInt("completed") == 1);
            }
        }
    }

    private List<Long> selectLegacyRows(Connection connection,
                                        String artifact,
                                        Long afterRowId,
                                        int batchSize) throws SQLException {
        String sql = "SELECT " + quote("id") + " FROM " + quote(artifact)
                + " WHERE " + quote("_lifecycle_id") + " IS NULL"
                + " AND " + quote("_first_confirmed_at_epoch_ms") + " IS NULL"
                + " AND " + quote("_last_confirmed_at_epoch_ms") + " IS NULL"
                + " AND " + quote("_valid_until_epoch_ms") + " IS NULL"
                + (afterRowId == null ? "" : " AND " + quote("id") + " > ?")
                + " ORDER BY " + quote("id") + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (afterRowId != null) {
                statement.setLong(parameter++, afterRowId);
            }
            statement.setInt(parameter, batchSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                var rows = new ArrayList<Long>(batchSize);
                while (resultSet.next()) {
                    rows.add(resultSet.getLong(1));
                }
                return rows;
            }
        }
    }

    private boolean hasLegacyRowsAfter(Connection connection,
                                       String artifact,
                                       Long afterRowId) throws SQLException {
        String sql = "SELECT 1 FROM " + quote(artifact)
                + " WHERE " + quote("_lifecycle_id") + " IS NULL"
                + (afterRowId == null ? "" : " AND " + quote("id") + " > ?")
                + " LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (afterRowId != null) {
                statement.setLong(1, afterRowId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void requestProjection(Connection connection,
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
    }

    private void updateProgress(Connection connection,
                                String artifact,
                                Long afterRowId,
                                long expiredCount,
                                boolean completed,
                                EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE lifecycle_activation_progress
                SET after_row_id = ?, expired_count = ?, completed = ?, updated_at_ms = ?
                WHERE artifact = ? AND completed = 0
                """)) {
            if (afterRowId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, afterRowId);
            }
            statement.setLong(2, expiredCount);
            statement.setInt(3, completed ? 1 : 0);
            statement.setLong(4, asOf.value().toEpochMilli());
            statement.setString(5, artifact);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Lifecycle activation progress lost its writable state");
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

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private record ActivationProgress(Long afterRowId, long expiredCount, boolean completed) {
    }
}
