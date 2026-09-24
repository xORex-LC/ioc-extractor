package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.CanonicalWriteCommand;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRowConsumer;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactStreamReader;
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

/**
 * Canonical artifact repository backed by dataframe tables.
 *
 * <p>One write transaction owns public-row inserts, provenance updates and a
 * single artifact revision bump. Duplicate-only writes may update provenance
 * but preserve the public revision and report zero inserted rows.
 */
public final class JdbcCanonicalArtifactRepository
        implements CanonicalArtifactRepository, CanonicalArtifactStreamReader {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final LifecycleTimeSource activeTimeSource;
    private final JdbcCompatibilityArtifactWriter compatibilityWriter;

    public JdbcCanonicalArtifactRepository(DataSource dataSource,
                                           List<DataframeArtifactSchema> schemas,
                                           ArtifactIdentityResolver identityResolver,
                                           Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.compatibilityWriter = new JdbcCompatibilityArtifactWriter(
                dataSource, this.schemas, identityResolver, clock);
        this.activeTimeSource = () -> EffectiveTime.at(clock.instant());
    }

    /** Creates a repository whose active reads use the safe lifecycle clock. */
    public JdbcCanonicalArtifactRepository(DataSource dataSource,
                                           List<DataframeArtifactSchema> schemas,
                                           ArtifactIdentityResolver identityResolver,
                                           Clock clock,
                                           LifecycleTimeSource activeTimeSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.compatibilityWriter = new JdbcCompatibilityArtifactWriter(
                dataSource, this.schemas, identityResolver, clock);
        this.activeTimeSource = Objects.requireNonNull(activeTimeSource, "activeTimeSource");
    }

    @Override
    public CanonicalArtifact load(String artifactName) {
        DataframeArtifactSchema schema = schema(artifactName);
        List<String> header = header(schema);
        List<ArtifactRow> rows = new ArrayList<>();
        stream(artifactName, rows::add);
        return new CanonicalArtifact(artifactName, header, rows);
    }

    @Override
    public int stream(String artifactName, CanonicalArtifactRowConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        DataframeArtifactSchema schema = schema(artifactName);
        List<String> header = header(schema);
        try (Connection connection = dataSource.getConnection()) {
            LifecycleActivationState state = JdbcLifecycleTransactions.readActivationState(connection);
            EffectiveTime asOf = state != LifecycleActivationState.DISABLED_COMPATIBLE
                    ? Objects.requireNonNull(activeTimeSource.now(), "lifecycle effective time")
                    : null;
            return stream(connection, artifactName, header, state, asOf, consumer);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to stream JDBC artifact: " + artifactName, e);
        }
    }

    private int stream(Connection connection,
                       String artifactName,
                       List<String> header,
                       LifecycleActivationState expectedState,
                       EffectiveTime asOf,
                       CanonicalArtifactRowConsumer consumer) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            LifecycleActivationState state = JdbcLifecycleTransactions.readActivationState(connection);
            if (state != expectedState) {
                throw new IocExtractorException("Canonical lifecycle state changed while opening a read");
            }
            String activePredicate = state != LifecycleActivationState.DISABLED_COMPATIBLE
                    ? " WHERE " + quote("_valid_until_epoch_ms") + " > ?"
                    : "";
            String sql = "SELECT " + joinedQuoted(header) + " FROM " + quote(artifactName)
                    + activePredicate + " ORDER BY " + quote("id");
            int rows = 0;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (state != LifecycleActivationState.DISABLED_COMPATIBLE) {
                    statement.setLong(1, Objects.requireNonNull(asOf, "asOf").value().toEpochMilli());
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Map<String, String> values = new LinkedHashMap<>();
                        for (String column : header) {
                            values.put(column, resultSet.getString(column));
                        }
                        consumer.accept(ArtifactRow.ordered(values));
                        rows = Math.incrementExact(rows);
                    }
                }
            }
            connection.commit();
            return rows;
        } catch (SQLException | RuntimeException e) {
            failure = e;
            JdbcLifecycleTransactions.rollback(connection, e);
            throw e;
        } finally {
            JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
        }
    }

    @Override
    public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
        return compatibilityWriter.write(artifactName, artifact);
    }

    @Override
    public CanonicalWriteResult write(CanonicalWriteCommand command) {
        return compatibilityWriter.write(command);
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
                .map(column -> column.name())
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

}
