package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
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
 * Reconciles export-owned slot state and streams all artifacts of one resolved
 * export plan from a single SQLite read snapshot.
 *
 * <p>The reader owns the connection, transaction and every cursor for the full
 * synchronous callback sequence. It buffers only per-artifact metadata; public
 * rows are converted one at a time to {@link ArtifactRow} and are never collected
 * into a {@code CanonicalArtifact}.
 */
public final class JdbcSnapshotSliceReader implements SnapshotSliceReader {

    private static final int MAX_SLOT_SNAPSHOT_ATTEMPTS = 3;

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final Clock clock;
    private final LifecycleTimeSource activeTimeSource;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final JdbcExportSlotRegistry exportSlots;

    public JdbcSnapshotSliceReader(DataSource dataSource,
                                   List<DataframeArtifactSchema> schemas,
                                   Clock clock) {
        this(dataSource, schemas, clock, () -> EffectiveTime.at(clock.instant()),
                NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(clock));
    }

    public JdbcSnapshotSliceReader(DataSource dataSource,
                                   List<DataframeArtifactSchema> schemas,
                                   Clock clock,
                                   DiagnosticSink diagnosticSink,
                                   DiagnosticFactory diagnosticFactory) {
        this(dataSource, schemas, clock, () -> EffectiveTime.at(clock.instant()),
                diagnosticSink, diagnosticFactory);
    }

    /** Creates a snapshot reader whose active boundary uses the safe lifecycle clock. */
    public JdbcSnapshotSliceReader(DataSource dataSource,
                                   List<DataframeArtifactSchema> schemas,
                                   Clock clock,
                                   LifecycleTimeSource activeTimeSource,
                                   DiagnosticSink diagnosticSink,
                                   DiagnosticFactory diagnosticFactory) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activeTimeSource = Objects.requireNonNull(activeTimeSource, "activeTimeSource");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.exportSlots = new JdbcExportSlotRegistry();
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
        LifecycleActivationState expectedState = readActivationState(plan.profile().name());
        if (expectedState == LifecycleActivationState.ACTIVATING) {
            throw snapshotFailure(plan.profile().name(),
                    new SQLException("Canonical lifecycle activation is incomplete"));
        }
        Instant asOf = expectedState == LifecycleActivationState.ACTIVE
                ? activeTimeSource.now().value()
                : clock.instant();
        try {
            if (expectedState == LifecycleActivationState.ACTIVE) {
                return streamActive(plan, consumer, expectedState, asOf);
            }
            try (Connection connection = dataSource.getConnection()) {
                return stream(connection, plan, consumer, expectedState, asOf);
            }
        } catch (SQLException e) {
            throw snapshotFailure(plan.profile().name(), e);
        }
    }

    private SnapshotMetadata streamActive(ExportPlan plan,
                                          SnapshotRowConsumer consumer,
                                          LifecycleActivationState expectedState,
                                          Instant asOf) throws SQLException {
        JdbcExportSlotRegistry.SnapshotChangedException lastRace = null;
        for (int attempt = 1; attempt <= MAX_SLOT_SNAPSHOT_ATTEMPTS; attempt++) {
            reconcileExportSlots(plan, expectedState, asOf);
            try (Connection connection = dataSource.getConnection()) {
                return stream(connection, plan, consumer, expectedState, asOf);
            } catch (JdbcExportSlotRegistry.SnapshotChangedException changed) {
                lastRace = changed;
            }
        }
        throw new SQLException("Canonical data kept changing while opening export-slot snapshot", lastRace);
    }

    private void reconcileExportSlots(ExportPlan plan,
                                      LifecycleActivationState expectedState,
                                      Instant asOf) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                JdbcLifecycleTransactions.acquireActiveWriteOwnership(connection);
                LifecycleActivationState currentState = JdbcLifecycleTransactions.readActivationState(connection);
                if (currentState != expectedState) {
                    throw new SQLException(
                            "Canonical lifecycle state changed while reconciling export slots");
                }
                exportSlots.reconcile(connection, plan, asOf);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                failure = e;
                rollback(connection, e);
                throw e;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit, failure);
            }
        }
    }

    private SnapshotMetadata stream(Connection connection,
                                    ExportPlan plan,
                                    SnapshotRowConsumer consumer,
                                    LifecycleActivationState expectedState,
                                    Instant asOf) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            LifecycleActivationState lifecycleState = JdbcLifecycleTransactions.readActivationState(connection);
            if (lifecycleState != expectedState) {
                throw new SQLException("Canonical lifecycle state changed while opening an export snapshot");
            }
            List<SnapshotArtifactMetadata> artifacts = readMetadata(connection, plan, lifecycleState, asOf);
            SnapshotMetadata metadata = new SnapshotMetadata(
                    plan.profile().name(), plan.planHash(), asOf, artifacts);
            consumer.begin(metadata);
            for (SnapshotArtifactMetadata artifact : artifacts) {
                consumer.beginArtifact(artifact);
                streamRows(
                        connection, plan.profile().name(), artifact, lifecycleState, asOf, consumer);
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

    private LifecycleActivationState readActivationState(String profile) {
        try (Connection connection = dataSource.getConnection()) {
            return JdbcLifecycleTransactions.readActivationState(connection);
        } catch (SQLException e) {
            throw snapshotFailure(profile, e);
        }
    }

    /** Reads all coverage before callbacks so the first SELECT fixes the SQLite WAL snapshot. */
    private List<SnapshotArtifactMetadata> readMetadata(Connection connection,
                                                        ExportPlan plan,
                                                        LifecycleActivationState lifecycleState,
                                                        Instant asOf) throws SQLException {
        List<SnapshotArtifactMetadata> metadata = new ArrayList<>(plan.artifacts().size());
        for (ExportArtifactSpec artifact : plan.artifacts()) {
            if (lifecycleState == LifecycleActivationState.ACTIVE && exportSlots.appliesTo(artifact)) {
                exportSlots.requireCurrentSnapshot(
                        connection, plan.profile().name(), artifact.artifactName(), asOf);
            }
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
                    readCoverage(
                            connection, plan.profile().name(), artifact, lifecycleState, asOf),
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

    private ArtifactCoverage readCoverage(Connection connection,
                                          String profile,
                                          ExportArtifactSpec artifact,
                                          LifecycleActivationState lifecycleState,
                                          Instant asOf) throws SQLException {
        String artifactName = artifact.artifactName();
        long upperId = lifecycleState == LifecycleActivationState.ACTIVE
                && exportSlots.appliesTo(artifact)
                ? exportSlots.upperSlot(connection, profile, artifactName, asOf)
                : readCanonicalUpperId(connection, artifactName, lifecycleState, asOf);
        String sql = """
                SELECT COALESCE(r.revision, 0) AS revision,
                       r.changed_at
                FROM (SELECT 1) seed
                LEFT JOIN artifact_revision r ON r.artifact = ?
                """;
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
                        upperId);
            }
        }
    }

    private long readCanonicalUpperId(Connection connection,
                                      String artifactName,
                                      LifecycleActivationState lifecycleState,
                                      Instant asOf) throws SQLException {
        String sql = "SELECT COALESCE(MAX(" + quote("id") + "), 0) FROM " + quote(artifactName)
                + (lifecycleState == LifecycleActivationState.ACTIVE
                ? " WHERE " + quote("_valid_until_epoch_ms") + " > ?" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (lifecycleState == LifecycleActivationState.ACTIVE) {
                statement.setLong(1, asOf.toEpochMilli());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Coverage query returned no row for " + artifactName);
                }
                return resultSet.getLong(1);
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
                            String profile,
                            SnapshotArtifactMetadata artifact,
                            LifecycleActivationState lifecycleState,
                            Instant asOf,
                            SnapshotRowConsumer consumer) throws SQLException {
        boolean slotted = lifecycleState == LifecycleActivationState.ACTIVE
                && artifact.columns().contains("id");
        String columns = artifact.columns().stream()
                .map(column -> slotted && "id".equals(column)
                        ? "a." + quote("slot") + " AS " + quote("id")
                        : "t." + quote(column) + " AS " + quote(column))
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + columns + " FROM " + quote(artifact.artifactName()) + " t"
                + (slotted
                ? " JOIN export_slot_assignment a"
                + " ON a.profile = ? AND a.artifact = ?"
                + " AND a.lifecycle_id = t." + quote("_lifecycle_id")
                : "")
                + (lifecycleState == LifecycleActivationState.ACTIVE
                ? " WHERE t." + quote("_valid_until_epoch_ms") + " > ?" : "")
                + " ORDER BY " + (slotted ? "a." + quote("slot") : "t." + quote("id"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (slotted) {
                statement.setString(parameter++, profile);
                statement.setString(parameter++, artifact.artifactName());
            }
            if (lifecycleState == LifecycleActivationState.ACTIVE) {
                statement.setLong(parameter, asOf.toEpochMilli());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (String column : artifact.columns()) {
                        values.put(column, resultSet.getString(column));
                    }
                    consumer.row(ArtifactRow.ordered(values));
                }
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
