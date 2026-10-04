package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalMutationEngine.StoredLifecycle;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalMatchCardinality;
import com.iocextractor.application.artifact.CanonicalMatchPlan;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalRecordMutationKind;
import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleId;
import com.iocextractor.application.artifact.lifecycle.ValidityDecision;
import com.iocextractor.application.observation.RegisteredObservation;
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

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.bind;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.epochMillis;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.placeholders;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/**
 * Thread-confined mutation session for one immutable artifact catalog and effective time.
 * The caller owns the transaction; close releases statements and private matcher state.
 */
final class JdbcCanonicalMutationSession implements AutoCloseable {
    private final Connection connection;
    private final DataframeArtifactSchema schema;
    private final EffectiveTime asOf;
    private final ValidityDecision validity;
    private final CanonicalArtifactKeyResolver keyResolver;
    private final JdbcCanonicalMatchSession matchPlanner;
    private final JdbcLifecycleArchive lifecycleArchive;
    private final JdbcOrderedFieldStore orderedFields;
    private final JdbcStatementScope statements;
    private final JdbcCanonicalStoredRowReader rowReader;
    private final String rowKeySql;
    private final String insertSql;
    private final String sourceSql;

    JdbcCanonicalMutationSession(Connection connection, DataframeArtifactSchema schema,
                                 EffectiveTime asOf, ValidityDecision validity,
                                 CanonicalArtifactKeyResolver keyResolver,
                                 JdbcCanonicalMatchPlanner matchPlanner,
                                 JdbcLifecycleArchive lifecycleArchive,
                                 JdbcOrderedFieldStore orderedFields) throws SQLException {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException("Mutation session requires a caller-owned transaction");
        }
        this.schema = Objects.requireNonNull(schema, "schema");
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.validity = Objects.requireNonNull(validity, "validity").requireValidAt(asOf);
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.matchPlanner = matchPlanner.openSession(connection, schema, asOf);
        this.lifecycleArchive = lifecycleArchive;
        this.orderedFields = orderedFields;
        this.statements = new JdbcStatementScope(connection);
        this.rowReader = new JdbcCanonicalStoredRowReader(schema);
        this.rowKeySql = "SELECT " + quote("id") + " FROM " + quote(schema.artifactName())
                + " WHERE " + quote("row_key") + " = ?";
        List<String> columns = new ArrayList<>(schema.columns().stream().map(DataframeColumn::name).toList());
        columns.addAll(List.of("row_key", "_created_at", "_first_source_key", "_lifecycle_id",
                "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms", "_valid_until_epoch_ms"));
        this.insertSql = "INSERT INTO " + quote(schema.artifactName()) + " (" + joinedQuoted(columns)
                + ") VALUES (" + placeholders(columns.size()) + ")";
        this.sourceSql = JdbcCanonicalSourceRecorder.sql(schema.artifactName());
    }

    /** Confirms one ordinary-ingest observation through the shared active matcher. */
    CanonicalRecordMutationOutcome confirm(String sourceKey,
                                           CanonicalRecordConfirmation confirmation,
                                           Long publicId,
                                           LifecycleId lifecycleId,
                                           RegisteredObservation registration) throws SQLException {
        ArtifactRow incoming = confirmation.preparedRow().idColumn().isPresent()
                ? confirmation.preparedRow().materialize(publicId)
                : confirmation.preparedRow().template();
        ArtifactRowKey recordKey = resolvedRecordKey(schema.artifactName(), incoming)
                .orElse(confirmation.rowKey());

        List<CanonicalKeyMaterial> matchKeys =
                keyResolver.matchKeysOf(schema.artifactName(), incoming);
        CanonicalMatchPlan plan = matchPlanner.plan(
                List.of(new CanonicalMatchRequest("ordinary", matchKeys))).getFirst();
        if (plan.cardinality() == CanonicalMatchCardinality.MULTIPLE) {
            throw new IocExtractorException("Multiple active canonical matches for artifact: "
                    + schema.artifactName());
        }

        var exactCandidate = plan.exactCandidate();
        Optional<StoredLifecycle> stored = exactCandidate.isPresent()
                ? Optional.of(loadStored(exactCandidate.orElseThrow().canonicalRowId()))
                : findStored(recordKey.value());
        if (stored.isEmpty()) {
            long rowId = insertActive(sourceKey, incoming, recordKey,
                    lifecycleId);
            orderedFields.initializeLifecycle(statements, schema.artifactName(), lifecycleId.value(),
                    confirmation.preparedRow(), registration);
            replaceAliases(rowId, lifecycleId.value(), matchKeys);
            return outcome(CanonicalRecordMutationKind.INSERTED, rowId, lifecycleId.value());
        }

        StoredLifecycle current = stored.orElseThrow();
        if (current.validUntilEpochMs() > epochMillis(asOf)) {
            var resolution = orderedFields.resolveLifecycle(
                    statements, schema.artifactName(), current.lifecycleId(), current.publicRow(),
                    confirmation.preparedRow(), registration);
            if (resolution.publicChanged()) {
                updatePublicRow(current.rowId(), resolution.finalRow(),
                        List.copyOf(resolution.publicChangedFields()));
            }
            renewActive(sourceKey, current);
            replaceAliases(current.rowId(), current.lifecycleId(), resolution.finalRow());
            CanonicalRecordMutationKind kind = resolution.publicChanged()
                    ? CanonicalRecordMutationKind.UPDATED
                    : CanonicalRecordMutationKind.TTL_CONFIRMED;
            return new CanonicalRecordMutationOutcome(
                    kind, current.rowId(), current.lifecycleId(),
                    resolution.publicChangedFields(), Set.of(), resolution.metadataChanged());
        }

        lifecycleArchive.archiveAndDelete(connection, schema, current.rowId(), asOf);
        long rowId = insertActive(sourceKey, incoming, recordKey,
                lifecycleId);
        orderedFields.initializeLifecycle(statements, schema.artifactName(), lifecycleId.value(),
                confirmation.preparedRow(), registration);
        replaceAliases(rowId, lifecycleId.value(), matchKeys);
        return outcome(CanonicalRecordMutationKind.RESTARTED, rowId, lifecycleId.value());
    }

    /** Applies a pre-resolved import patch and records its source occurrence. */
    CanonicalRecordMutationOutcome mutateExisting(long canonicalRowId,
                                                   ArtifactRow finalRow,
                                                   boolean renewTtl,
                                                   String sourceKey) throws SQLException {
        StoredLifecycle stored = loadStored(canonicalRowId);
        if (stored.validUntilEpochMs() <= epochMillis(asOf)) {
            throw new IocExtractorException("Cannot mutate an expired canonical lifecycle");
        }
        ArtifactRowKey resolvedKey = resolvedRecordKey(schema.artifactName(), finalRow)
                .orElse(stored.rowKey());
        if (!stored.rowKey().equals(resolvedKey)) {
            throw new IocExtractorException("Canonical record-key mutation must create a new record");
        }

        PublicRowChanges changes = detectPublicRowChanges(stored.publicRow(), finalRow);
        if (changes.hasChanges()) {
            updatePublicRow(canonicalRowId, finalRow, changes.columns());
            replaceAliases(canonicalRowId, stored.lifecycleId(), finalRow);
        }
        if (renewTtl) {
            renewLifecycleOnly(stored);
        }
        if (sourceKey != null) {
            recordSource(canonicalRowId,
                    sourceKey, asOf.value().toString());
        }
        return new CanonicalRecordMutationOutcome(
                changes.mutationKind(renewTtl), canonicalRowId, stored.lifecycleId(),
                changes.updated(), changes.cleared());
    }

    /** Applies import merge output while resolving configured fields by durable admission order. */
    CanonicalRecordMutationOutcome mutateExistingOrdered(
            long canonicalRowId,
            ArtifactRow mergedRow,
            PreparedArtifactRow incoming,
            boolean renewTtl,
            String sourceKey,
            RegisteredObservation registration) throws SQLException {
        StoredLifecycle stored = loadStored(canonicalRowId);
        if (stored.validUntilEpochMs() <= epochMillis(asOf)) {
            throw new IocExtractorException("Cannot mutate an expired canonical lifecycle");
        }
        var resolution = orderedFields.resolveLifecycle(
                statements, schema.artifactName(), stored.lifecycleId(), stored.publicRow(),
                incoming, registration);
        ArtifactRow resolved = mergedRow;
        for (String field : incoming.orderedFieldPositions().keySet()) {
            resolved = resolved.withValue(field, resolution.finalRow().value(field));
        }
        ArtifactRowKey resolvedKey = resolvedRecordKey(schema.artifactName(), resolved)
                .orElse(stored.rowKey());
        if (!stored.rowKey().equals(resolvedKey)) {
            throw new IocExtractorException("Canonical record-key mutation must create a new record");
        }
        PublicRowChanges changes = detectPublicRowChanges(stored.publicRow(), resolved);
        if (changes.hasChanges()) {
            updatePublicRow(canonicalRowId, resolved, changes.columns());
            replaceAliases(canonicalRowId, stored.lifecycleId(), resolved);
        }
        if (renewTtl) {
            renewLifecycleOnly(stored);
        }
        recordSource(canonicalRowId,
                sourceKey, asOf.value().toString());
        return new CanonicalRecordMutationOutcome(
                changes.mutationKind(renewTtl), canonicalRowId, stored.lifecycleId(),
                changes.updated(), changes.cleared(), resolution.metadataChanged());
    }

    /** Inserts or restarts one already planned import branch without re-matching. */
    CanonicalRecordMutationOutcome insertPlanned(String sourceKey,
                                                 ArtifactRow incoming,
                                                 ArtifactRowKey recordKey,
                                                 LifecycleId lifecycleId) throws SQLException {
        Optional<StoredLifecycle> sameKey = findStored(recordKey.value());
        CanonicalRecordMutationKind kind = CanonicalRecordMutationKind.INSERTED;
        if (sameKey.isPresent()) {
            StoredLifecycle stored = sameKey.orElseThrow();
            if (stored.validUntilEpochMs() > epochMillis(asOf)) {
                throw new IocExtractorException("Active canonical record-key collision after import planning");
            }
            lifecycleArchive.archiveAndDelete(connection, schema, stored.rowId(), asOf);
            kind = CanonicalRecordMutationKind.RESTARTED;
        }
        long rowId = insertActive(sourceKey, incoming, recordKey,
                lifecycleId);
        replaceAliases(rowId, lifecycleId.value(), incoming);
        return outcome(kind, rowId, lifecycleId.value());
    }

    /** Inserts a planned import branch and initializes ordered-field provenance atomically. */
    CanonicalRecordMutationOutcome insertPlannedOrdered(
            String sourceKey,
            PreparedArtifactRow incoming,
            ArtifactRowKey recordKey,
            LifecycleId lifecycleId,
            RegisteredObservation registration) throws SQLException {
        CanonicalRecordMutationOutcome outcome = insertPlanned(sourceKey, incoming.template(), recordKey,
                lifecycleId);
        orderedFields.initializeLifecycle(statements, schema.artifactName(), lifecycleId.value(),
                incoming, registration);
        return new CanonicalRecordMutationOutcome(
                outcome.kind(), outcome.canonicalRowId(), outcome.lifecycleId(),
                outcome.updatedFields(), outcome.clearedFields(), true);
    }

    private PublicRowChanges detectPublicRowChanges(ArtifactRow stored,
                                                    ArtifactRow incoming) {
        Set<String> updated = new LinkedHashSet<>();
        Set<String> cleared = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>();
        for (DataframeColumn column : schema.columns()) {
            if ("id".equals(column.name())) {
                continue;
            }
            String before = stored.value(column.name());
            String after = incoming.value(column.name());
            if (!Objects.equals(before, after)) {
                changed.add(column.name());
                if (after == null) {
                    cleared.add(column.name());
                } else {
                    updated.add(column.name());
                }
            }
        }
        return new PublicRowChanges(changed, updated, cleared);
    }

    private Optional<StoredLifecycle> findStored(String rowKey) {
        String sql = rowKeySql;
        try (var statementLease = statements.borrow(sql)) {
            PreparedStatement statement = statementLease.statement();
            statement.setString(1, rowKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(loadStored(resultSet.getLong(1)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to inspect canonical row key", e);
        }
    }

    private StoredLifecycle loadStored(long rowId) {
        try (var lease = statements.borrow(rowReader.sql())) {
            return rowReader.load(lease.statement(), rowId);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to load canonical lifecycle", e);
        }
    }

    private long insertActive(String sourceKey,
                              ArtifactRow row,
                              ArtifactRowKey rowKey,
                              LifecycleId lifecycleId) throws SQLException {
        List<Object> values = new ArrayList<>();
        for (DataframeColumn column : schema.columns()) {
            values.add(row.value(column.name()));
        }
        values.add(rowKey.value());
        values.add(asOf.value().toString());
        values.add(sourceKey);
        values.add(lifecycleId.value());
        values.add(epochMillis(asOf));
        values.add(epochMillis(asOf));
        values.add(validity.deadline().validUntil().toEpochMilli());

        String sql = insertSql;
        try (var statementLease = statements.borrow(sql)) {
            PreparedStatement statement = statementLease.statement();
            bind(statement, values);
            statement.executeUpdate();
        }
        long rowId = requireRowId(rowKey.value());
        recordSource(rowId, sourceKey, asOf.value().toString());
        return rowId;
    }

    private void renewActive(String sourceKey,
                             StoredLifecycle stored) throws SQLException {
        renewLifecycleOnly(stored);
        recordSource(stored.rowId(), sourceKey, asOf.value().toString());
    }

    private void renewLifecycleOnly(StoredLifecycle stored) throws SQLException {
        String sql = "UPDATE " + quote(schema.artifactName()) + " SET "
                + quote("_last_confirmed_at_epoch_ms") + " = ?, "
                + quote("_valid_until_epoch_ms") + " = ? WHERE " + quote("id") + " = ? AND "
                + quote("_valid_until_epoch_ms") + " > ?";
        try (var statementLease = statements.borrow(sql)) {
            PreparedStatement statement = statementLease.statement();
            statement.setLong(1, epochMillis(asOf));
            statement.setLong(2, validity.deadline().validUntil().toEpochMilli());
            statement.setLong(3, stored.rowId());
            statement.setLong(4, epochMillis(asOf));
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Active lifecycle changed during confirmation");
            }
        }
    }

    private void updatePublicRow(long rowId,
                                 ArtifactRow finalRow,
                                 List<String> changed) throws SQLException {
        String assignments = changed.stream().map(column -> quote(column) + " = ?")
                .collect(Collectors.joining(", "));
        try (var statementLease = statements.borrow(
                "UPDATE " + quote(schema.artifactName()) + " SET " + assignments
                        + " WHERE " + quote("id") + " = ?")) {
            PreparedStatement statement = statementLease.statement();
            for (int index = 0; index < changed.size(); index++) {
                statement.setString(index + 1, finalRow.value(changed.get(index)));
            }
            statement.setLong(changed.size() + 1, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Canonical row disappeared during public mutation");
            }
        }
    }

    private void replaceAliases(long rowId, long lifecycleId, ArtifactRow row) throws SQLException {
        replaceAliases(rowId, lifecycleId, keyResolver.matchKeysOf(schema.artifactName(), row));
    }

    private void replaceAliases(long rowId,
                                long lifecycleId,
                                List<CanonicalKeyMaterial> keys) throws SQLException {
        try (var deleteLease = statements.borrow(
                "DELETE FROM canonical_match_alias WHERE artifact = ? AND lifecycle_id = ?")) {
            PreparedStatement delete = deleteLease.statement();
            delete.setString(1, schema.artifactName());
            delete.setLong(2, lifecycleId);
            delete.executeUpdate();
        }
        try (var insertLease = statements.borrow("""
                INSERT INTO canonical_match_alias(
                    artifact, definition_id, key_hash, key_canonical, lifecycle_id, canonical_row_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            PreparedStatement insert = insertLease.statement();
            for (var key : keys) {
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

    private long requireRowId(String rowKey) throws SQLException {
        try (var statementLease = statements.borrow(
                rowKeySql)) {
            PreparedStatement statement = statementLease.statement();
            statement.setString(1, rowKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IocExtractorException("Inserted lifecycle row is not readable");
                }
                return resultSet.getLong(1);
            }
        }
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

    private void recordSource(long rowId, String sourceKey, String observedAt) throws SQLException {
        try (var lease = statements.borrow(sourceSql)) {
            JdbcCanonicalSourceRecorder.record(lease.statement(), rowId, sourceKey, observedAt);
        }
    }

    @Override
    public void close() throws SQLException {
        try (matchPlanner) {
            statements.close();
        }
    }

    private record PublicRowChanges(List<String> columns,
                                    Set<String> updated,
                                    Set<String> cleared) {

        private PublicRowChanges {
            columns = List.copyOf(columns);
            updated = Set.copyOf(updated);
            cleared = Set.copyOf(cleared);
        }

        private boolean hasChanges() {
            return !columns.isEmpty();
        }

        private CanonicalRecordMutationKind mutationKind(boolean renewTtl) {
            if (!cleared.isEmpty()) {
                return CanonicalRecordMutationKind.CLEARED;
            }
            if (!updated.isEmpty()) {
                return CanonicalRecordMutationKind.UPDATED;
            }
            return renewTtl ? CanonicalRecordMutationKind.TTL_CONFIRMED : CanonicalRecordMutationKind.NO_OP;
        }
    }
}
