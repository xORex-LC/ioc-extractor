package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical artifact repository backed by dataframe tables.
 */
public final class JdbcCanonicalArtifactRepository implements CanonicalArtifactRepository {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final ArtifactIdentityResolver identityResolver;
    private final Clock clock;

    public JdbcCanonicalArtifactRepository(DataSource dataSource,
                                           List<DataframeArtifactSchema> schemas,
                                           ArtifactIdentityResolver identityResolver,
                                           Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CanonicalArtifact load(String artifactName) {
        DataframeArtifactSchema schema = schema(artifactName);
        List<String> header = header(schema);
        String sql = "SELECT " + joinedQuoted(header) + " FROM " + quote(artifactName) + " ORDER BY "
                + quote("id");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<ArtifactRow> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : header) {
                    values.put(column, resultSet.getString(column));
                }
                rows.add(ArtifactRow.ordered(values));
            }
            return new CanonicalArtifact(artifactName, header, rows);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to load JDBC artifact: " + artifactName, e);
        }
    }

    @Override
    public void write(String artifactName, CanonicalArtifact artifact) {
        DataframeArtifactSchema schema = schema(artifactName);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (ArtifactRow row : artifact.rows()) {
                    insertRow(connection, schema, row);
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to write JDBC artifact: " + artifactName, e);
        }
    }

    private void insertRow(Connection connection, DataframeArtifactSchema schema, ArtifactRow row)
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

        String sql = "INSERT INTO " + quote(schema.artifactName()) + "(" + joinedQuoted(columns) + ") VALUES ("
                + "?,".repeat(columns.size()).replaceFirst(",$", "")
                + ") ON CONFLICT(" + quote("row_key") + ") DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                statement.setString(i + 1, values.get(i));
            }
            statement.executeUpdate();
        }
        upsertSource(connection, schema.artifactName(), rowKey.value(), sourceKey, observedAt);
    }

    private void upsertSource(Connection connection,
                              String artifactName,
                              String rowKey,
                              String sourceKey,
                              String observedAt) throws SQLException {
        String sourceTable = artifactName + "_sources";
        Long rowId = rowId(connection, artifactName, rowKey);
        if (rowId == null) {
            return;
        }
        String sql = "INSERT INTO " + quote(sourceTable)
                + " (" + joinedQuoted(List.of("row_id", "source_key", "first_seen_at", "last_seen_at", "occurrences"))
                + ") VALUES (?, ?, ?, ?, 1) ON CONFLICT(" + quote("row_id") + ", " + quote("source_key") + ") "
                + "DO UPDATE SET " + quote("last_seen_at") + " = excluded." + quote("last_seen_at")
                + ", " + quote("occurrences") + " = " + quote("occurrences") + " + 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rowId);
            statement.setString(2, sourceKey);
            statement.setString(3, observedAt);
            statement.setString(4, observedAt);
            statement.executeUpdate();
        }
    }

    private Long rowId(Connection connection, String artifactName, String rowKey) throws SQLException {
        String sql = "SELECT " + quote("id") + " FROM " + quote(artifactName)
                + " WHERE " + quote("row_key") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rowKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private String sourceKey(ArtifactRow row) {
        String sourceKey = row.value("_source_key");
        if (sourceKey == null || sourceKey.isBlank()) {
            sourceKey = row.value("source");
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            return "unknown";
        }
        return sourceKey;
    }

    private Map<String, DataframeArtifactSchema> schemasByName(List<DataframeArtifactSchema> source) {
        Map<String, DataframeArtifactSchema> byName = new LinkedHashMap<>();
        for (DataframeArtifactSchema schema : source) {
            byName.put(schema.artifactName(), schema);
        }
        return Map.copyOf(byName);
    }

    private DataframeArtifactSchema schema(String artifactName) {
        DataframeArtifactSchema schema = schemas.get(artifactName);
        if (schema == null) {
            throw new IocExtractorException("Unknown dataframe artifact: " + artifactName);
        }
        return schema;
    }

    private List<String> header(DataframeArtifactSchema schema) {
        return schema.columns().stream()
                .map(DataframeColumn::name)
                .toList();
    }

    private String joinedQuoted(List<String> identifiers) {
        return identifiers.stream()
                .map(this::quote)
                .collect(java.util.stream.Collectors.joining(", "));
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
}
