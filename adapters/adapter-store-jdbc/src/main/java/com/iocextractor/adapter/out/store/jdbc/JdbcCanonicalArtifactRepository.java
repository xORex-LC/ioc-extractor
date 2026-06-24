package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.port.out.aggregation.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
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
        values.add(clock.instant().toString());
        columns.add("_first_source_key");
        values.add(null);

        String sql = "INSERT INTO " + quote(schema.artifactName()) + "(" + joinedQuoted(columns) + ") VALUES ("
                + "?,".repeat(columns.size()).replaceFirst(",$", "")
                + ") ON CONFLICT(" + quote("row_key") + ") DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                statement.setString(i + 1, values.get(i));
            }
            statement.executeUpdate();
        }
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
