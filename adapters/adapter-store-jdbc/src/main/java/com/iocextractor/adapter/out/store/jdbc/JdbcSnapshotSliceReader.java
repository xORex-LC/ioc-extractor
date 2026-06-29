package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Streams all artifacts of one resolved export plan from a single SQLite read snapshot.
 *
 * <p>The reader owns the connection, transaction and every cursor for the full
 * synchronous callback sequence. It buffers only per-artifact metadata; public
 * rows are converted one at a time to {@link ArtifactRow} and are never collected
 * into a {@code CanonicalArtifact}.
 */
public final class JdbcSnapshotSliceReader implements SnapshotSliceReader {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final Clock clock;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;

    public JdbcSnapshotSliceReader(DataSource dataSource,
                                   List<DataframeArtifactSchema> schemas,
                                   Clock clock) {
        this(dataSource, schemas, clock, NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(clock));
    }

    public JdbcSnapshotSliceReader(DataSource dataSource,
                                   List<DataframeArtifactSchema> schemas,
                                   Clock clock,
                                   DiagnosticSink diagnosticSink,
                                   DiagnosticFactory diagnosticFactory) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    /**
     * Establishes one read transaction, captures coverage for every artifact,
     * and then streams ordered rows through a synchronous callback protocol.
     * Consumer failures are propagated unchanged after rollback and cleanup.
     */
    @Override
    public SnapshotMetadata stream(SnapshotRequest request, SnapshotRowConsumer consumer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(consumer, "consumer");
        ExportPlan plan = request.plan();
        validatePlan(plan);
        try (Connection connection = dataSource.getConnection()) {
            return stream(connection, plan, consumer);
        } catch (SQLException e) {
            throw snapshotFailure(plan.profile().name(), e);
        }
    }

    private SnapshotMetadata stream(Connection connection,
                                    ExportPlan plan,
                                    SnapshotRowConsumer consumer) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            List<SnapshotArtifactMetadata> artifacts = readMetadata(connection, plan);
            SnapshotMetadata metadata = new SnapshotMetadata(
                    plan.profile().name(), plan.planHash(), clock.instant(), artifacts);
            consumer.begin(metadata);
            for (SnapshotArtifactMetadata artifact : artifacts) {
                consumer.beginArtifact(artifact);
                streamRows(connection, artifact, consumer);
                consumer.endArtifact();
            }
            consumer.end();
            connection.commit();
            return metadata;
        } catch (SQLException | RuntimeException e) {
            failure = e;
            rollback(connection, e);
            throw e;
        } finally {
            restoreAutoCommit(connection, previousAutoCommit, failure);
        }
    }

    /** Reads all coverage before callbacks so the first SELECT fixes the SQLite WAL snapshot. */
    private List<SnapshotArtifactMetadata> readMetadata(Connection connection,
                                                        ExportPlan plan) throws SQLException {
        List<SnapshotArtifactMetadata> metadata = new ArrayList<>(plan.artifacts().size());
        for (ExportArtifactSpec artifact : plan.artifacts()) {
            StoredIdentity identity = readIdentity(connection, artifact.artifactName());
            if (identity.epoch() != artifact.identityEpoch()
                    || !Objects.equals(identity.hash(), artifact.identityHash())) {
                throw new SQLException("Export identity metadata does not match canonical store: "
                        + artifact.artifactName());
            }
            metadata.add(new SnapshotArtifactMetadata(
                    artifact.artifactName(),
                    artifact.fileName(),
                    artifact.columns(),
                    readCoverage(connection, artifact.artifactName()),
                    identity.epoch(),
                    identity.hash(),
                    artifact.schemaHash()));
        }
        return List.copyOf(metadata);
    }

    private StoredIdentity readIdentity(Connection connection, String artifactName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT identity_hash, epoch
                FROM artifact_identity
                WHERE artifact = ?
                """)) {
            statement.setString(1, artifactName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException(
                            "Missing canonical identity metadata for artifact: " + artifactName);
                }
                return new StoredIdentity(resultSet.getString("identity_hash"), resultSet.getInt("epoch"));
            }
        }
    }

    private ArtifactCoverage readCoverage(Connection connection, String artifactName) throws SQLException {
        String sql = """
                SELECT COALESCE(r.revision, 0) AS revision,
                       r.changed_at,
                       (SELECT COALESCE(MAX(id), 0) FROM %s) AS upper_id
                FROM (SELECT 1) seed
                LEFT JOIN artifact_revision r ON r.artifact = ?
                """.formatted(quote(artifactName));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifactName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Coverage query returned no row for " + artifactName);
                }
                return coverage(
                        artifactName,
                        resultSet.getLong("revision"),
                        resultSet.getString("changed_at"),
                        resultSet.getLong("upper_id"));
            }
        }
    }

    private ArtifactCoverage coverage(String artifactName,
                                      long revision,
                                      String changedAt,
                                      long upperId) throws SQLException {
        try {
            return new ArtifactCoverage(
                    revision,
                    changedAt == null ? null : Instant.parse(changedAt),
                    upperId);
        } catch (DateTimeParseException | IllegalArgumentException invalidMetadata) {
            throw new SQLException("Invalid canonical coverage metadata for artifact: "
                    + artifactName, invalidMetadata);
        }
    }

    private void streamRows(Connection connection,
                            SnapshotArtifactMetadata artifact,
                            SnapshotRowConsumer consumer) throws SQLException {
        String columns = artifact.columns().stream()
                .map(this::quote)
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + columns + " FROM " + quote(artifact.artifactName())
                + " ORDER BY " + quote("id");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : artifact.columns()) {
                    values.put(column, resultSet.getString(column));
                }
                consumer.row(ArtifactRow.ordered(values));
            }
        }
    }

    private void validatePlan(ExportPlan plan) {
        for (ExportArtifactSpec artifact : plan.artifacts()) {
            DataframeArtifactSchema schema = schemas.get(artifact.artifactName());
            if (schema == null) {
                throw new IllegalArgumentException("Unknown dataframe artifact: " + artifact.artifactName());
            }
            Set<String> available = schema.columns().stream()
                    .map(column -> column.name())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!available.containsAll(artifact.columns())) {
                Set<String> unknown = new LinkedHashSet<>(artifact.columns());
                unknown.removeAll(available);
                throw new IllegalArgumentException("Unknown public columns for artifact "
                        + artifact.artifactName() + ": " + unknown);
            }
            artifact.columns().forEach(column -> DataframeColumn.requireSqlIdentifier(column, "export column"));
        }
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

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private void restoreAutoCommit(Connection connection,
                                   boolean autoCommit,
                                   Exception original) throws SQLException {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException restoreFailure) {
            if (original == null) {
                throw restoreFailure;
            }
            original.addSuppressed(restoreFailure);
        }
    }

    private DiagnosticException snapshotFailure(String profile, SQLException cause) {
        Diagnostic diagnostic = diagnosticFactory.create(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED)
                .with("profile", profile)
                .with("reason", Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()))
                .cause(cause)
                .build();
        diagnosticSink.emit(diagnostic);
        return new DiagnosticException(diagnostic);
    }

    private record StoredIdentity(String hash, int epoch) {
    }
}
