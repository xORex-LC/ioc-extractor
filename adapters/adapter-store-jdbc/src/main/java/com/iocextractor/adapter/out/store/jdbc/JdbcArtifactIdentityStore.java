package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.StoredArtifactIdentity;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityStore;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JDBC-backed guardrail and collision-safe backfill for canonical key formulas.
 *
 * <p>Every configured artifact is staged before durable rows are changed. The
 * temporary table's unique key is therefore the migration collision preflight;
 * one failure rolls back every artifact and leaves readiness closed.
 */
public final class JdbcArtifactIdentityStore implements ArtifactIdentityStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcArtifactIdentityStore.class);
    private static final String MIGRATION_ROW_KEY_PREFIX = "__ioc_identity_v7__:";

    private final DataSource dataSource;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final Clock clock;
    private final String dbRole;

    public JdbcArtifactIdentityStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(clock), "dataframe");
    }

    public JdbcArtifactIdentityStore(DataSource dataSource,
                                     Clock clock,
                                     DiagnosticSink diagnosticSink,
                                     DiagnosticFactory diagnosticFactory,
                                     String dbRole) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        if (dbRole == null || dbRole.isBlank()) {
            throw new IllegalArgumentException("dbRole is required");
        }
        this.dbRole = dbRole;
    }

    @Override
    public StoredArtifactIdentity ensure(ArtifactIdentityDefinition definition) {
        return ensureAll(List.of(Objects.requireNonNull(definition, "definition"))).getFirst();
    }

    @Override
    public List<StoredArtifactIdentity> ensureAll(List<ArtifactIdentityDefinition> definitions) {
        List<ArtifactIdentityDefinition> requested = List.copyOf(
                Objects.requireNonNull(definitions, "definitions"));
        validateUniqueArtifacts(requested);
        try (Connection connection = dataSource.getConnection()) {
            return ensureAll(connection, requested);
        } catch (DiagnosticException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw identityMigrationFailure(requested, e);
        }
    }

    private List<StoredArtifactIdentity> ensureAll(Connection connection,
                                                   List<ArtifactIdentityDefinition> definitions)
            throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        List<EpochBump> epochBumps = new ArrayList<>();
        Exception failure = null;
        try {
            List<ArtifactIdentityDefinition> pending = new ArrayList<>();
            for (ArtifactIdentityDefinition definition : definitions) {
                Optional<StoredDefinition> stored = load(connection, definition.artifactName());
                validateIdentity(definition, stored);
                validateNamedDefinitions(connection, definition);
                if (!isCurrent(connection, definition, stored)) {
                    pending.add(definition);
                    stored.filter(value -> value.epoch() < definition.epoch())
                            .ifPresent(value -> epochBumps.add(new EpochBump(value.identity(), definition)));
                }
            }
            if (!pending.isEmpty()) {
                createShadowTables(connection);
                for (ArtifactIdentityDefinition definition : pending) {
                    stageArtifact(connection, definition);
                }
                applyStagedMigration(connection, pending);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            failure = e;
            rollback(connection, e);
            throw e;
        } finally {
            restoreAutoCommit(connection, previousAutoCommit, failure);
        }
        epochBumps.forEach(bump -> emitEpochBump(bump.stored(), bump.definition()));
        return definitions.stream()
                .map(definition -> new StoredArtifactIdentity(
                        definition.artifactName(), definition.identityHash(), definition.epoch()))
                .toList();
    }

    private void createShadowTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS temp.ioc_identity_shadow");
            statement.execute("DROP TABLE IF EXISTS temp.ioc_alias_shadow");
            statement.execute("""
                    CREATE TEMP TABLE ioc_identity_shadow (
                        artifact TEXT NOT NULL,
                        canonical_row_id INTEGER NOT NULL,
                        row_key TEXT NOT NULL,
                        PRIMARY KEY (artifact, canonical_row_id),
                        UNIQUE (artifact, row_key)
                    )
                    """);
            statement.execute("""
                    CREATE TEMP TABLE ioc_alias_shadow (
                        artifact TEXT NOT NULL,
                        definition_id TEXT NOT NULL,
                        key_hash TEXT NOT NULL,
                        key_canonical TEXT NOT NULL,
                        lifecycle_id INTEGER NOT NULL,
                        canonical_row_id INTEGER NOT NULL,
                        PRIMARY KEY (artifact, definition_id, key_hash, key_canonical, lifecycle_id)
                    )
                    """);
        }
    }

    private void stageArtifact(Connection connection, ArtifactIdentityDefinition definition) throws SQLException {
        List<String> columns = keyColumns(definition);
        String selected = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String sql = "SELECT " + quote("id") + ", " + quote("_lifecycle_id")
                + (selected.isEmpty() ? "" : ", " + selected)
                + " FROM " + quote(definition.artifactName()) + " ORDER BY " + quote("id");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql);
             PreparedStatement identity = connection.prepareStatement("""
                     INSERT INTO temp.ioc_identity_shadow(artifact, canonical_row_id, row_key)
                     VALUES (?, ?, ?)
                     """);
             PreparedStatement alias = connection.prepareStatement("""
                     INSERT INTO temp.ioc_alias_shadow(
                         artifact, definition_id, key_hash, key_canonical,
                         lifecycle_id, canonical_row_id)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            while (resultSet.next()) {
                long rowId = resultSet.getLong("id");
                Long lifecycleId = nullableLong(resultSet, "_lifecycle_id");
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : columns) {
                    values.put(column, resultSet.getString(column));
                }
                ArtifactRow row = ArtifactRow.ordered(values);
                CanonicalKeyMaterial recordKey = CanonicalArtifactKeyResolver
                        .materialOf(definition.recordKey(), row)
                        .orElseThrow(() -> new IllegalStateException(
                                "Canonical record key is empty for " + definition.artifactName()
                                        + " row " + rowId));
                identity.setString(1, definition.artifactName());
                identity.setLong(2, rowId);
                identity.setString(3, recordKey.keyHash());
                identity.executeUpdate();

                if (lifecycleId != null) {
                    for (CanonicalKeyDefinition matchKey : definition.matchKeys()) {
                        Optional<CanonicalKeyMaterial> material = CanonicalArtifactKeyResolver.materialOf(matchKey, row);
                        if (material.isPresent()) {
                            CanonicalKeyMaterial value = material.orElseThrow();
                            alias.setString(1, definition.artifactName());
                            alias.setString(2, value.definitionId());
                            alias.setString(3, value.keyHash());
                            alias.setString(4, value.keyCanonical());
                            alias.setLong(5, lifecycleId);
                            alias.setLong(6, rowId);
                            alias.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    private void applyStagedMigration(Connection connection,
                                      List<ArtifactIdentityDefinition> definitions) throws SQLException {
        long activatedAt = clock.instant().toEpochMilli();
        for (ArtifactIdentityDefinition definition : definitions) {
            guardReservedKeys(connection, definition.artifactName());
            registerDefinitions(connection, definition, activatedAt);
            try (PreparedStatement placeholder = connection.prepareStatement(
                    "UPDATE " + quote(definition.artifactName()) + " SET " + quote("row_key")
                            + " = ? || " + quote("id") + " WHERE " + quote("id")
                            + " IN (SELECT canonical_row_id FROM temp.ioc_identity_shadow WHERE artifact = ?)")) {
                placeholder.setString(1, MIGRATION_ROW_KEY_PREFIX + definition.artifactName() + ":");
                placeholder.setString(2, definition.artifactName());
                placeholder.executeUpdate();
            }
            try (PreparedStatement finalKeys = connection.prepareStatement(
                    "UPDATE " + quote(definition.artifactName()) + " SET " + quote("row_key")
                            + " = (SELECT row_key FROM temp.ioc_identity_shadow s"
                            + " WHERE s.artifact = ? AND s.canonical_row_id = "
                            + quote(definition.artifactName()) + "." + quote("id") + ")"
                            + " WHERE " + quote("id")
                            + " IN (SELECT canonical_row_id FROM temp.ioc_identity_shadow WHERE artifact = ?)")) {
                finalKeys.setString(1, definition.artifactName());
                finalKeys.setString(2, definition.artifactName());
                finalKeys.executeUpdate();
            }
            replaceAliases(connection, definition.artifactName());
            upsertIdentity(connection, definition);
        }
    }

    private void registerDefinitions(Connection connection,
                                     ArtifactIdentityDefinition definition,
                                     long activatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO canonical_match_definition(
                    artifact, definition_id, definition_fingerprint, identity_epoch, activated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(artifact, definition_id) DO NOTHING
                """)) {
            for (CanonicalKeyDefinition matchKey : definition.matchKeys()) {
                statement.setString(1, definition.artifactName());
                statement.setString(2, matchKey.definitionId());
                statement.setString(3, matchKey.fingerprint());
                statement.setInt(4, definition.epoch());
                statement.setLong(5, activatedAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceAliases(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM canonical_match_alias WHERE artifact = ?")) {
            delete.setString(1, artifact);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO canonical_match_alias(
                    artifact, definition_id, key_hash, key_canonical, lifecycle_id, canonical_row_id)
                SELECT artifact, definition_id, key_hash, key_canonical, lifecycle_id, canonical_row_id
                FROM temp.ioc_alias_shadow
                WHERE artifact = ?
                """)) {
            insert.setString(1, artifact);
            insert.executeUpdate();
        }
    }

    private void upsertIdentity(Connection connection, ArtifactIdentityDefinition definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO artifact_identity(
                    artifact, identity_hash, epoch, applied_at,
                    record_definition_id, record_definition_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(artifact) DO UPDATE SET
                    identity_hash = excluded.identity_hash,
                    epoch = excluded.epoch,
                    applied_at = excluded.applied_at,
                    record_definition_id = excluded.record_definition_id,
                    record_definition_fingerprint = excluded.record_definition_fingerprint
                """)) {
            statement.setString(1, definition.artifactName());
            statement.setString(2, definition.identityHash());
            statement.setInt(3, definition.epoch());
            statement.setString(4, clock.instant().toString());
            statement.setString(5, definition.recordKey().definitionId());
            statement.setString(6, definition.recordKey().fingerprint());
            statement.executeUpdate();
        }
    }

    private Optional<StoredDefinition> load(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT artifact, identity_hash, epoch,
                       record_definition_id, record_definition_fingerprint
                FROM artifact_identity
                WHERE artifact = ?
                """)) {
            statement.setString(1, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredDefinition(
                        new StoredArtifactIdentity(resultSet.getString("artifact"),
                                resultSet.getString("identity_hash"), resultSet.getInt("epoch")),
                        resultSet.getString("record_definition_id"),
                        resultSet.getString("record_definition_fingerprint")));
            }
        }
    }

    private void validateIdentity(ArtifactIdentityDefinition definition,
                                  Optional<StoredDefinition> existing) {
        if (existing.isEmpty()) {
            return;
        }
        StoredDefinition stored = existing.orElseThrow();
        if (stored.epoch() > definition.epoch()) {
            throw identityDrift(definition, stored.identity(), "configured epoch is older than stored epoch");
        }
        if (!stored.identityHash().equals(definition.identityHash())
                && definition.epoch() <= stored.epoch()) {
            throw identityDrift(definition, stored.identity(), "identity hash changed without epoch bump");
        }
        if (stored.recordDefinitionId() != null
                && stored.recordDefinitionId().equals(definition.recordKey().definitionId())
                && !stored.recordDefinitionFingerprint().equals(definition.recordKey().fingerprint())) {
            throw identityDrift(definition, stored.identity(), "named record-key definition is immutable");
        }
        if (stored.recordDefinitionId() != null
                && !stored.recordDefinitionId().equals(definition.recordKey().definitionId())
                && definition.epoch() <= stored.epoch()) {
            throw identityDrift(definition, stored.identity(), "record-key definition changed without epoch bump");
        }
    }

    private void validateNamedDefinitions(Connection connection,
                                          ArtifactIdentityDefinition definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition_fingerprint
                FROM canonical_match_definition
                WHERE artifact = ? AND definition_id = ?
                """)) {
            for (CanonicalKeyDefinition matchKey : definition.matchKeys()) {
                statement.setString(1, definition.artifactName());
                statement.setString(2, matchKey.definitionId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && !matchKey.fingerprint().equals(resultSet.getString(1))) {
                        StoredArtifactIdentity stored = load(connection, definition.artifactName())
                                .map(StoredDefinition::identity)
                                .orElse(new StoredArtifactIdentity(
                                        definition.artifactName(), definition.identityHash(), definition.epoch()));
                        throw identityDrift(definition, stored, "stored match-key definition is immutable");
                    }
                }
            }
        }
    }

    private boolean isCurrent(Connection connection,
                              ArtifactIdentityDefinition definition,
                              Optional<StoredDefinition> stored) throws SQLException {
        if (stored.isEmpty()) {
            return false;
        }
        StoredDefinition value = stored.orElseThrow();
        if (value.epoch() != definition.epoch()
                || !value.identityHash().equals(definition.identityHash())
                || !definition.recordKey().definitionId().equals(value.recordDefinitionId())
                || !definition.recordKey().fingerprint().equals(value.recordDefinitionFingerprint())) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition_fingerprint
                FROM canonical_match_definition
                WHERE artifact = ? AND definition_id = ?
                """)) {
            for (CanonicalKeyDefinition matchKey : definition.matchKeys()) {
                statement.setString(1, definition.artifactName());
                statement.setString(2, matchKey.definitionId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || !matchKey.fingerprint().equals(resultSet.getString(1))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void guardReservedKeys(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + quote(artifact) + " WHERE " + quote("row_key") + " LIKE ? LIMIT 1")) {
            statement.setString(1, MIGRATION_ROW_KEY_PREFIX + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new IllegalStateException("Reserved identity migration row-key prefix is already in use");
                }
            }
        }
    }

    private List<String> keyColumns(ArtifactIdentityDefinition definition) {
        var result = new java.util.LinkedHashSet<String>(definition.recordKey().columns());
        definition.matchKeys().forEach(match -> result.addAll(match.columns()));
        return List.copyOf(result);
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private void validateUniqueArtifacts(List<ArtifactIdentityDefinition> definitions) {
        if (definitions.stream().map(ArtifactIdentityDefinition::artifactName).distinct().count()
                != definitions.size()) {
            throw new IllegalArgumentException("Artifact identity definitions must be unique");
        }
    }

    private DiagnosticException identityMigrationFailure(List<ArtifactIdentityDefinition> definitions,
                                                         Throwable cause) {
        String artifacts = definitions.stream().map(ArtifactIdentityDefinition::artifactName)
                .collect(Collectors.joining(","));
        Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.IDENTITY_DRIFT)
                .with("artifact", artifacts)
                .with("identityEpoch", 0)
                .with("reason", "collision or invalid canonical key during identity migration")
                .cause(cause)
                .build();
        diagnosticSink.emit(diagnostic);
        return new DiagnosticException(diagnostic);
    }

    private DiagnosticException identityDrift(ArtifactIdentityDefinition definition,
                                              StoredArtifactIdentity stored,
                                              String reason) {
        Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.IDENTITY_DRIFT)
                .with("artifact", definition.artifactName())
                .with("identityEpoch", stored.epoch())
                .with("reason", reason)
                .build();
        diagnosticSink.emit(diagnostic);
        LogEvents.error(LOGGER)
                .action(EventAction.SCHEMA_VALIDATE)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_DB_ROLE, dbRole)
                .field(LogField.IOC_ARTIFACT_NAME, definition.artifactName())
                .field(LogField.IOC_IDENTITY_EPOCH, stored.epoch())
                .message("artifact identity drift refused")
                .log();
        return new DiagnosticException(diagnostic);
    }

    private void emitEpochBump(StoredArtifactIdentity stored, ArtifactIdentityDefinition definition) {
        Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.IDENTITY_EPOCH_BUMP)
                .with("artifact", definition.artifactName())
                .with("fromEpoch", stored.epoch())
                .with("toEpoch", definition.epoch())
                .build();
        diagnosticSink.emit(diagnostic);
        LogEvents.info(LOGGER)
                .action(EventAction.BACKFILL)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_DB_ROLE, dbRole)
                .field(LogField.IOC_ARTIFACT_NAME, definition.artifactName())
                .field(LogField.IOC_IDENTITY_EPOCH, definition.epoch())
                .message("artifact identity epoch bumped")
                .log();
    }

    private void rollback(Connection connection, Exception original) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private void restoreAutoCommit(Connection connection, boolean previous, Exception original) throws SQLException {
        try {
            connection.setAutoCommit(previous);
        } catch (SQLException restoreFailure) {
            if (original != null) {
                original.addSuppressed(restoreFailure);
            } else {
                throw restoreFailure;
            }
        }
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private record StoredDefinition(StoredArtifactIdentity identity,
                                    String recordDefinitionId,
                                    String recordDefinitionFingerprint) {
        private int epoch() {
            return identity.epoch();
        }

        private String identityHash() {
            return identity.identityHash();
        }
    }

    private record EpochBump(StoredArtifactIdentity stored, ArtifactIdentityDefinition definition) {
    }
}
