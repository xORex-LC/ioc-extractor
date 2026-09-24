package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteCommand;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Owns lifecycle-disabled canonical insert and ordered-field mutation transactions. */
final class JdbcCompatibilityArtifactWriter {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final ArtifactIdentityResolver identityResolver;
    private final Clock clock;
    private final JdbcOrderedFieldStore orderedFields = new JdbcOrderedFieldStore();

    JdbcCompatibilityArtifactWriter(DataSource dataSource,
                                    Map<String, DataframeArtifactSchema> schemas,
                                    ArtifactIdentityResolver identityResolver,
                                    Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = Map.copyOf(Objects.requireNonNull(schemas, "schemas"));
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
        DataframeArtifactSchema schema = schema(artifactName);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                acquireWriteOwnership(connection);
                int inserted = 0;
                for (ArtifactRow row : artifact.rows()) {
                    if (insertRow(connection, schema, row)) {
                        inserted++;
                    }
                }
                long revision = inserted > 0
                        ? bumpRevision(connection, artifactName, clock.instant().toString())
                        : currentRevision(connection, artifactName);
                connection.commit();
                return new CanonicalWriteResult(inserted, revision);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to write JDBC artifact: " + artifactName, failure);
        }
    }

    CanonicalWriteResult write(CanonicalWriteCommand command) {
        Objects.requireNonNull(command, "command");
        boolean ordered = command.rows().stream()
                .anyMatch(row -> !row.orderedFieldPositions().isEmpty());
        if (!ordered) {
            return write(command.artifactName(), command.artifact());
        }
        DataframeArtifactSchema schema = schema(command.artifactName());
        if (!header(schema).equals(command.header())) {
            throw new IllegalArgumentException(
                    "Canonical write header does not match artifact schema: " + command.artifactName());
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                acquireWriteOwnership(connection);
                var registration = command.registrationOptional().orElseThrow();
                orderedFields.validateRegistration(connection, registration);
                long identityEpoch = identityEpoch(connection, command.artifactName());
                MutationCounts counts = applyRows(
                        connection, schema, command, identityEpoch, registration);
                int publicChanges = Math.addExact(counts.inserted(), counts.updated());
                long revision = publicChanges > 0
                        ? bumpRevision(connection, command.artifactName(), clock.instant().toString())
                        : currentRevision(connection, command.artifactName());
                connection.commit();
                return new CanonicalWriteResult(
                        counts.inserted(), counts.updated(), counts.metadataOnly(), revision);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw new IocExtractorException(
                    "Failed ordered JDBC artifact write: " + command.artifactName(), failure);
        }
    }

    private MutationCounts applyRows(Connection connection,
                                     DataframeArtifactSchema schema,
                                     CanonicalWriteCommand command,
                                     long identityEpoch,
                                     com.iocextractor.application.observation.RegisteredObservation registration)
            throws SQLException {
        int inserted = 0;
        int updated = 0;
        int metadataOnly = 0;
        for (var row : command.rows()) {
            var rowKey = identityResolver.keyOf(command.artifactName(), row.row())
                    .orElseThrow(() -> new IocExtractorException(
                            "Cannot resolve row_key for artifact " + command.artifactName()));
            Long existingId = rowId(connection, command.artifactName(), rowKey.value());
            var prepared = new PreparedArtifactRow(
                    row.row(), Optional.empty(), row.orderedFieldPositions());
            if (existingId == null) {
                if (!insertRow(connection, schema, row.row())) {
                    throw new IocExtractorException(
                            "Canonical row appeared during serialized compatibility write");
                }
                orderedFields.initializeCompatibility(
                        connection, command.artifactName(), rowKey.value(), identityEpoch,
                        prepared, registration);
                inserted++;
                continue;
            }
            ArtifactRow current = loadPublicRow(connection, schema, existingId);
            var resolution = orderedFields.resolveCompatibility(
                    connection, command.artifactName(), rowKey.value(), identityEpoch,
                    current, prepared, registration);
            if (resolution.publicChanged()) {
                updatePublicFields(connection, schema, existingId,
                        resolution.finalRow(), resolution.publicChangedFields());
                updated++;
            } else if (resolution.metadataChanged()) {
                metadataOnly++;
            }
            recordSource(connection, command.artifactName(), rowKey.value(),
                    sourceKey(row.row()), clock.instant().toString());
        }
        return new MutationCounts(inserted, updated, metadataOnly);
    }

    private long identityEpoch(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT epoch FROM artifact_identity WHERE artifact = ?")) {
            statement.setString(1, artifact);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IocExtractorException("Missing artifact identity epoch: " + artifact);
                }
                return result.getLong(1);
            }
        }
    }

    private ArtifactRow loadPublicRow(Connection connection,
                                      DataframeArtifactSchema schema,
                                      long rowId) throws SQLException {
        List<String> columns = header(schema);
        String sql = "SELECT " + joinedQuoted(columns) + " FROM " + quote(schema.artifactName())
                + " WHERE " + quote("id") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rowId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IocExtractorException("Canonical row disappeared during ordered write");
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : columns) {
                    values.put(column, result.getString(column));
                }
                return ArtifactRow.ordered(values);
            }
        }
    }

    private void updatePublicFields(Connection connection,
                                    DataframeArtifactSchema schema,
                                    long rowId,
                                    ArtifactRow row,
                                    Set<String> fields) throws SQLException {
        String assignments = fields.stream()
                .map(field -> quote(field) + " = ?")
                .collect(Collectors.joining(", "));
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + quote(schema.artifactName()) + " SET " + assignments
                        + " WHERE " + quote("id") + " = ?")) {
            int index = 1;
            for (String field : fields) {
                statement.setString(index++, row.value(field));
            }
            statement.setLong(index, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Canonical row disappeared during ordered field update");
            }
        }
    }

    private void acquireWriteOwnership(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE canonical_lifecycle_control
                SET version = version
                WHERE singleton_id = 1
                  AND state = 'DISABLED_COMPATIBLE'
                """)) {
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException(
                        "Legacy canonical writer is disabled after lifecycle activation starts");
            }
        }
    }

    private boolean insertRow(Connection connection, DataframeArtifactSchema schema, ArtifactRow row)
            throws SQLException {
        var rowKey = identityResolver.keyOf(schema.artifactName(), row)
                .orElseThrow(() -> new IocExtractorException("Cannot resolve row_key for artifact "
                        + schema.artifactName()));
        String observedAt = clock.instant().toString();
        String sourceKey = sourceKey(row);
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        String explicitId = row.value("id");
        if (explicitId != null && !explicitId.isBlank()) {
            columns.add("id");
            values.add(explicitId);
        }
        for (DataframeColumn column : schema.columns()) {
            if (!"id".equals(column.name())) {
                columns.add(column.name());
                values.add(row.value(column.name()));
            }
        }
        columns.add("row_key");
        values.add(rowKey.value());
        columns.add("_created_at");
        values.add(observedAt);
        columns.add("_first_source_key");
        values.add(sourceKey);

        String sql = "INSERT INTO " + quote(schema.artifactName()) + "(" + joinedQuoted(columns)
                + ") VALUES (" + "?,".repeat(columns.size()).replaceFirst(",$", "")
                + ") ON CONFLICT(" + quote("row_key") + ") DO NOTHING";
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                statement.setString(i + 1, values.get(i));
            }
            inserted = statement.executeUpdate();
        }
        recordSource(connection, schema.artifactName(), rowKey.value(), sourceKey, observedAt);
        return inserted > 0;
    }

    private long bumpRevision(Connection connection, String artifactName, String changedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO artifact_revision(artifact, revision, changed_at)
                VALUES (?, 1, ?)
                ON CONFLICT(artifact) DO UPDATE SET
                    revision = artifact_revision.revision + 1,
                    changed_at = excluded.changed_at
                """)) {
            statement.setString(1, artifactName);
            statement.setString(2, changedAt);
            statement.executeUpdate();
        }
        return currentRevision(connection, artifactName);
    }

    private long currentRevision(Connection connection, String artifactName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM artifact_revision WHERE artifact = ?")) {
            statement.setString(1, artifactName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private void recordSource(Connection connection,
                              String artifactName,
                              String rowKey,
                              String sourceKey,
                              String observedAt) throws SQLException {
        Long canonicalRowId = rowId(connection, artifactName, rowKey);
        if (canonicalRowId != null) {
            JdbcCanonicalSourceRecorder.record(
                    connection, artifactName, canonicalRowId, sourceKey, observedAt);
        }
    }

    private Long rowId(Connection connection, String artifactName, String rowKey) throws SQLException {
        String sql = "SELECT " + quote("id") + " FROM " + quote(artifactName)
                + " WHERE " + quote("row_key") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rowKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private String sourceKey(ArtifactRow row) {
        String sourceKey = row.value("_source_key");
        if (sourceKey == null || sourceKey.isBlank()) {
            sourceKey = row.value("source");
        }
        return sourceKey == null || sourceKey.isBlank() ? "unknown" : sourceKey;
    }

    private DataframeArtifactSchema schema(String artifactName) {
        DataframeArtifactSchema schema = schemas.get(artifactName);
        if (schema == null) {
            throw new IocExtractorException("Unknown dataframe artifact: " + artifactName);
        }
        return schema;
    }

    private List<String> header(DataframeArtifactSchema schema) {
        return schema.columns().stream().map(DataframeColumn::name).toList();
    }

    private String joinedQuoted(List<String> identifiers) {
        return identifiers.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private void rollback(Connection connection, Exception original) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record MutationCounts(int inserted, int updated, int metadataOnly) {
    }
}
