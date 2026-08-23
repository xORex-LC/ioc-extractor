package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalMatchCardinality;
import com.iocextractor.application.artifact.CanonicalMatchPlan;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.CanonicalRecordMutationKind;
import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleId;
import com.iocextractor.application.artifact.lifecycle.ValidityDecision;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Connection-scoped canonical record mutation kernel shared by ingest and import.
 *
 * <p>The caller owns transaction, effective time, identity reservations,
 * revision aggregation and durable receipt publication.
 */
public final class JdbcCanonicalMutationEngine {

    private final CanonicalArtifactKeyResolver keyResolver;
    private final JdbcCanonicalMatchPlanner matchPlanner;
    private final JdbcLifecycleArchive lifecycleArchive;

    /** Creates a kernel for one immutable schema and key catalog. */
    public JdbcCanonicalMutationEngine(javax.sql.DataSource dataSource,
                                       List<DataframeArtifactSchema> schemas,
                                       List<ArtifactIdentityDefinition> definitions) {
        this.keyResolver = new CanonicalArtifactKeyResolver(definitions);
        this.matchPlanner = new JdbcCanonicalMatchPlanner(dataSource, schemas);
        this.lifecycleArchive = new JdbcLifecycleArchive();
    }

    /** Confirms one ordinary-ingest observation through the shared active matcher. */
    CanonicalRecordMutationOutcome confirm(Connection connection,
                                           DataframeArtifactSchema schema,
                                           String sourceKey,
                                           CanonicalRecordConfirmation confirmation,
                                           Long publicId,
                                           LifecycleId lifecycleId,
                                           EffectiveTime asOf,
                                           ValidityDecision validity) throws SQLException {
        ArtifactRow incoming = confirmation.preparedRow().idColumn().isPresent()
                ? confirmation.preparedRow().materialize(publicId)
                : confirmation.preparedRow().template();
        ArtifactRowKey recordKey = resolvedRecordKey(schema.artifactName(), incoming)
                .orElse(confirmation.rowKey());

        List<com.iocextractor.application.artifact.CanonicalKeyMaterial> matchKeys =
                keyResolver.matchKeysOf(schema.artifactName(), incoming);
        CanonicalMatchPlan plan = matchPlanner.plan(
                connection, schema, asOf, List.of(new CanonicalMatchRequest("ordinary", matchKeys))).getFirst();
        if (plan.cardinality() == CanonicalMatchCardinality.MULTIPLE) {
            throw new IocExtractorException("Multiple active canonical matches for artifact: "
                    + schema.artifactName());
        }

        var exactCandidate = plan.exactCandidate();
        Optional<StoredLifecycle> stored = exactCandidate.isPresent()
                ? Optional.of(loadStored(connection, schema, exactCandidate.orElseThrow().canonicalRowId()))
                : findStored(connection, schema, recordKey.value());
        if (stored.isEmpty()) {
            long rowId = insertActive(connection, schema, sourceKey, incoming, recordKey,
                    lifecycleId, asOf, validity);
            replaceAliases(connection, schema, rowId, lifecycleId.value(), incoming);
            return outcome(CanonicalRecordMutationKind.INSERTED, rowId, lifecycleId.value());
        }

        StoredLifecycle current = stored.orElseThrow();
        if (current.validUntilEpochMs() > epochMillis(asOf)) {
            renewActive(connection, schema, sourceKey, current, asOf, validity);
            replaceAliases(connection, schema, current.rowId(), current.lifecycleId(), current.publicRow());
            return outcome(CanonicalRecordMutationKind.TTL_CONFIRMED, current.rowId(), current.lifecycleId());
        }

        lifecycleArchive.archiveAndDelete(connection, schema, current.rowId(), asOf);
        long rowId = insertActive(connection, schema, sourceKey, incoming, recordKey,
                lifecycleId, asOf, validity);
        replaceAliases(connection, schema, rowId, lifecycleId.value(), incoming);
        return outcome(CanonicalRecordMutationKind.RESTARTED, rowId, lifecycleId.value());
    }

    /**
     * Applies a pre-resolved patch result and exposes update/clear/no-op semantics.
     * This entry point is used by the later delivery-scoped import writer.
     */
    public CanonicalRecordMutationOutcome mutateExisting(Connection connection,
                                                         DataframeArtifactSchema schema,
                                                         long canonicalRowId,
                                                         ArtifactRow finalRow,
                                                         boolean renewTtl,
                                                         EffectiveTime asOf,
                                                         ValidityDecision validity) throws SQLException {
        StoredLifecycle stored = loadStored(connection, schema, canonicalRowId);
        if (stored.validUntilEpochMs() <= epochMillis(asOf)) {
            throw new IocExtractorException("Cannot mutate an expired canonical lifecycle");
        }
        ArtifactRowKey resolvedKey = resolvedRecordKey(schema.artifactName(), finalRow)
                .orElse(stored.rowKey());
        if (!stored.rowKey().equals(resolvedKey)) {
            throw new IocExtractorException("Canonical record-key mutation must create a new record");
        }

        Set<String> updated = new LinkedHashSet<>();
        Set<String> cleared = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>();
        for (DataframeColumn column : schema.columns()) {
            if ("id".equals(column.name())) {
                continue;
            }
            String before = stored.publicRow().value(column.name());
            String after = finalRow.value(column.name());
            if (!Objects.equals(before, after)) {
                changed.add(column.name());
                if (after == null) {
                    cleared.add(column.name());
                } else {
                    updated.add(column.name());
                }
            }
        }
        if (!changed.isEmpty()) {
            updatePublicRow(connection, schema, canonicalRowId, finalRow, changed);
            replaceAliases(connection, schema, canonicalRowId, stored.lifecycleId(), finalRow);
        }
        if (renewTtl) {
            renewLifecycleOnly(connection, schema, stored, asOf, validity);
        }
        CanonicalRecordMutationKind kind = !cleared.isEmpty()
                ? CanonicalRecordMutationKind.CLEARED
                : !updated.isEmpty() ? CanonicalRecordMutationKind.UPDATED
                : renewTtl ? CanonicalRecordMutationKind.TTL_CONFIRMED : CanonicalRecordMutationKind.NO_OP;
        return new CanonicalRecordMutationOutcome(kind, canonicalRowId, stored.lifecycleId(), updated, cleared);
    }

    private Optional<StoredLifecycle> findStored(Connection connection,
                                                 DataframeArtifactSchema schema,
                                                 String rowKey) {
        String sql = "SELECT " + quote("id") + " FROM " + quote(schema.artifactName())
                + " WHERE " + quote("row_key") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rowKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(loadStored(connection, schema, resultSet.getLong(1)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to inspect canonical row key", e);
        }
    }

    private StoredLifecycle loadStored(Connection connection,
                                       DataframeArtifactSchema schema,
                                       long rowId) {
        List<String> publicColumns = schema.columns().stream().map(DataframeColumn::name).toList();
        String sql = "SELECT " + quote("id") + ", " + quote("row_key") + ", "
                + quote("_lifecycle_id") + ", " + quote("_first_confirmed_at_epoch_ms") + ", "
                + quote("_last_confirmed_at_epoch_ms") + ", " + quote("_valid_until_epoch_ms")
                + (publicColumns.isEmpty() ? "" : ", " + joinedQuoted(publicColumns))
                + " FROM " + quote(schema.artifactName()) + " WHERE " + quote("id") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rowId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IocExtractorException("Canonical match alias points to a missing row");
                }
                long lifecycleId = requiredLong(resultSet, "_lifecycle_id", schema.artifactName());
                long firstConfirmed = requiredLong(resultSet, "_first_confirmed_at_epoch_ms", schema.artifactName());
                long lastConfirmed = requiredLong(resultSet, "_last_confirmed_at_epoch_ms", schema.artifactName());
                long validUntil = requiredLong(resultSet, "_valid_until_epoch_ms", schema.artifactName());
                if (lifecycleId <= 0 || firstConfirmed > lastConfirmed || lastConfirmed >= validUntil) {
                    throw new IocExtractorException(
                            "Active lifecycle row has invalid ordered metadata: " + schema.artifactName());
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : publicColumns) {
                    values.put(column, resultSet.getString(column));
                }
                return new StoredLifecycle(rowId, lifecycleId, validUntil,
                        new ArtifactRowKey(resultSet.getString("row_key")), ArtifactRow.ordered(values));
            }
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to load canonical lifecycle", e);
        }
    }

    private long insertActive(Connection connection,
                              DataframeArtifactSchema schema,
                              String sourceKey,
                              ArtifactRow row,
                              ArtifactRowKey rowKey,
                              LifecycleId lifecycleId,
                              EffectiveTime asOf,
                              ValidityDecision validity) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (DataframeColumn column : schema.columns()) {
            columns.add(column.name());
            values.add(row.value(column.name()));
        }
        columns.add("row_key");
        values.add(rowKey.value());
        columns.add("_created_at");
        values.add(asOf.value().toString());
        columns.add("_first_source_key");
        values.add(sourceKey);
        columns.add("_lifecycle_id");
        values.add(lifecycleId.value());
        columns.add("_first_confirmed_at_epoch_ms");
        values.add(epochMillis(asOf));
        columns.add("_last_confirmed_at_epoch_ms");
        values.add(epochMillis(asOf));
        columns.add("_valid_until_epoch_ms");
        values.add(validity.deadline().validUntil().toEpochMilli());

        String sql = "INSERT INTO " + quote(schema.artifactName()) + " (" + joinedQuoted(columns)
                + ") VALUES (" + placeholders(columns.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
        long rowId = requireRowId(connection, schema.artifactName(), rowKey.value());
        upsertSource(connection, schema.artifactName(), rowId, sourceKey, asOf.value().toString());
        return rowId;
    }

    private void renewActive(Connection connection,
                             DataframeArtifactSchema schema,
                             String sourceKey,
                             StoredLifecycle stored,
                             EffectiveTime asOf,
                             ValidityDecision validity) throws SQLException {
        renewLifecycleOnly(connection, schema, stored, asOf, validity);
        upsertSource(connection, schema.artifactName(), stored.rowId(), sourceKey, asOf.value().toString());
    }

    private void renewLifecycleOnly(Connection connection,
                                    DataframeArtifactSchema schema,
                                    StoredLifecycle stored,
                                    EffectiveTime asOf,
                                    ValidityDecision validity) throws SQLException {
        String sql = "UPDATE " + quote(schema.artifactName()) + " SET "
                + quote("_last_confirmed_at_epoch_ms") + " = ?, "
                + quote("_valid_until_epoch_ms") + " = ? WHERE " + quote("id") + " = ? AND "
                + quote("_valid_until_epoch_ms") + " > ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, epochMillis(asOf));
            statement.setLong(2, validity.deadline().validUntil().toEpochMilli());
            statement.setLong(3, stored.rowId());
            statement.setLong(4, epochMillis(asOf));
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Active lifecycle changed during confirmation");
            }
        }
    }

    private void updatePublicRow(Connection connection,
                                 DataframeArtifactSchema schema,
                                 long rowId,
                                 ArtifactRow finalRow,
                                 List<String> changed) throws SQLException {
        String assignments = changed.stream().map(column -> quote(column) + " = ?")
                .collect(Collectors.joining(", "));
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + quote(schema.artifactName()) + " SET " + assignments
                        + " WHERE " + quote("id") + " = ?")) {
            for (int index = 0; index < changed.size(); index++) {
                statement.setString(index + 1, finalRow.value(changed.get(index)));
            }
            statement.setLong(changed.size() + 1, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Canonical row disappeared during public mutation");
            }
        }
    }

    private void replaceAliases(Connection connection,
                                DataframeArtifactSchema schema,
                                long rowId,
                                long lifecycleId,
                                ArtifactRow row) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM canonical_match_alias WHERE artifact = ? AND lifecycle_id = ?")) {
            delete.setString(1, schema.artifactName());
            delete.setLong(2, lifecycleId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO canonical_match_alias(
                    artifact, definition_id, key_hash, key_canonical, lifecycle_id, canonical_row_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (var key : keyResolver.matchKeysOf(schema.artifactName(), row)) {
                insert.setString(1, schema.artifactName());
                insert.setString(2, key.definitionId());
                insert.setString(3, key.keyHash());
                insert.setString(4, key.keyCanonical());
                insert.setLong(5, lifecycleId);
                insert.setLong(6, rowId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void upsertSource(Connection connection,
                              String artifact,
                              long rowId,
                              String sourceKey,
                              String observedAt) throws SQLException {
        String sql = "INSERT INTO " + quote(artifact + "_sources") + " ("
                + joinedQuoted(List.of("row_id", "source_key", "first_seen_at", "last_seen_at", "occurrences"))
                + ") VALUES (?, ?, ?, ?, 1) ON CONFLICT(" + quote("row_id") + ", " + quote("source_key")
                + ") DO UPDATE SET " + quote("last_seen_at") + " = excluded." + quote("last_seen_at")
                + ", " + quote("occurrences") + " = " + quote("occurrences") + " + 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rowId);
            statement.setString(2, sourceKey);
            statement.setString(3, observedAt);
            statement.setString(4, observedAt);
            statement.executeUpdate();
        }
    }

    private long requireRowId(Connection connection, String artifact, String rowKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + quote("id") + " FROM " + quote(artifact)
                        + " WHERE " + quote("row_key") + " = ?")) {
            statement.setString(1, rowKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IocExtractorException("Inserted lifecycle row is not readable");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private long requiredLong(ResultSet resultSet, String column, String artifact) throws SQLException {
        long value = resultSet.getLong(column);
        if (resultSet.wasNull()) {
            throw new IocExtractorException("Active lifecycle row is missing required metadata: " + artifact);
        }
        return value;
    }

    private CanonicalRecordMutationOutcome outcome(CanonicalRecordMutationKind kind,
                                                   long rowId,
                                                   long lifecycleId) {
        return new CanonicalRecordMutationOutcome(kind, rowId, lifecycleId, Set.of(), Set.of());
    }

    private Optional<ArtifactRowKey> resolvedRecordKey(String artifact, ArtifactRow row) {
        Optional<ArtifactRowKey> key = keyResolver.recordKeyOf(artifact, row)
                .map(material -> new ArtifactRowKey(material.keyHash()));
        if (key.isEmpty() && keyResolver.containsArtifact(artifact)) {
            throw new IocExtractorException("Canonical record key must contain at least one value: " + artifact);
        }
        return key;
    }

    private long epochMillis(EffectiveTime time) {
        return time.value().toEpochMilli();
    }

    private String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(ignored -> "?")
                .collect(Collectors.joining(", "));
    }

    private String joinedQuoted(List<String> identifiers) {
        return identifiers.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        statement.clearParameters();
        for (int index = 0; index < values.size(); index++) {
            statement.setObject(index + 1, values.get(index));
        }
    }

    private record StoredLifecycle(long rowId,
                                   long lifecycleId,
                                   long validUntilEpochMs,
                                   ArtifactRowKey rowKey,
                                   ArtifactRow publicRow) {
    }
}
