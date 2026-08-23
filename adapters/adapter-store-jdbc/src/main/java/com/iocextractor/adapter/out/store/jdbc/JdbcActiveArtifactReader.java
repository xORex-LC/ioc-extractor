package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.lifecycle.ActiveArtifactRecord;
import com.iocextractor.application.artifact.lifecycle.ActiveArtifactSnapshot;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleDeadline;
import com.iocextractor.application.artifact.lifecycle.LifecycleId;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.artifact.lifecycle.RecordLifecycle;
import com.iocextractor.application.port.out.artifact.lifecycle.ActiveArtifactReader;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** SQLite active-only lifecycle snapshot reader for one explicit effective time. */
public final class JdbcActiveArtifactReader implements ActiveArtifactReader {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;

    /** Creates the reader over the configured artifact catalog. */
    public JdbcActiveArtifactReader(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
    }

    @Override
    public ActiveArtifactSnapshot loadActive(String artifactName, EffectiveTime asOf) {
        Objects.requireNonNull(asOf, "asOf");
        DataframeArtifactSchema schema = schema(artifactName);
        try (Connection connection = dataSource.getConnection()) {
            return load(connection, schema, asOf);
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException("Failed to load active JDBC artifact: " + artifactName, e);
        }
    }

    private ActiveArtifactSnapshot load(Connection connection,
                                        DataframeArtifactSchema schema,
                                        EffectiveTime asOf) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            if (JdbcLifecycleTransactions.readActivationState(connection) != LifecycleActivationState.ACTIVE) {
                throw new IocExtractorException("Canonical lifecycle is not active");
            }
            List<ActiveArtifactRecord> records = readRecords(connection, schema, asOf);
            long revision = currentRevision(connection, schema.artifactName());
            ProjectionGeneration generation = currentGeneration(connection, schema.artifactName());
            connection.commit();
            return new ActiveArtifactSnapshot(
                    schema.artifactName(), publicHeader(schema), records, revision, generation, asOf);
        } catch (SQLException | RuntimeException e) {
            failure = e;
            JdbcLifecycleTransactions.rollback(connection, e);
            throw e;
        } finally {
            JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
        }
    }

    private List<ActiveArtifactRecord> readRecords(Connection connection,
                                                   DataframeArtifactSchema schema,
                                                   EffectiveTime asOf) throws SQLException {
        List<String> header = publicHeader(schema);
        String publicColumns = header.stream().map(this::quote).collect(Collectors.joining(", "));
        String sql = "SELECT " + quote("row_key") + ", " + quote("_lifecycle_id") + ", "
                + quote("_first_confirmed_at_epoch_ms") + ", "
                + quote("_last_confirmed_at_epoch_ms") + ", "
                + quote("_valid_until_epoch_ms") + ", " + publicColumns
                + " FROM " + quote(schema.artifactName())
                + " WHERE " + quote("_valid_until_epoch_ms") + " > ?"
                + " ORDER BY " + quote("id");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf.value().toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ActiveArtifactRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (String column : header) {
                        values.put(column, resultSet.getString(column));
                    }
                    records.add(new ActiveArtifactRecord(
                            new ArtifactRowKey(resultSet.getString("row_key")),
                            ArtifactRow.ordered(values),
                            lifecycle(resultSet)));
                }
                return List.copyOf(records);
            }
        }
    }

    private RecordLifecycle lifecycle(ResultSet resultSet) throws SQLException {
        return new RecordLifecycle(
                new LifecycleId(requiredLong(resultSet, "_lifecycle_id")),
                EffectiveTime.at(Instant.ofEpochMilli(requiredLong(
                        resultSet, "_first_confirmed_at_epoch_ms"))),
                EffectiveTime.at(Instant.ofEpochMilli(requiredLong(
                        resultSet, "_last_confirmed_at_epoch_ms"))),
                new LifecycleDeadline(Instant.ofEpochMilli(requiredLong(
                        resultSet, "_valid_until_epoch_ms"))));
    }

    private long requiredLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        if (resultSet.wasNull()) {
            throw new IocExtractorException("Active lifecycle row is missing required metadata: " + column);
        }
        return value;
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

    private ProjectionGeneration currentGeneration(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT required_generation FROM artifact_projection_state WHERE artifact = ?")) {
            statement.setString(1, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                return new ProjectionGeneration(resultSet.next() ? resultSet.getLong(1) : 0L);
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
        for (DataframeArtifactSchema schema : source) {
            if (result.put(schema.artifactName(), schema) != null) {
                throw new IllegalArgumentException("Duplicate dataframe artifact schema: " + schema.artifactName());
            }
        }
        return Map.copyOf(result);
    }

    private List<String> publicHeader(DataframeArtifactSchema schema) {
        return schema.columns().stream().map(DataframeColumn::name).toList();
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }
}
