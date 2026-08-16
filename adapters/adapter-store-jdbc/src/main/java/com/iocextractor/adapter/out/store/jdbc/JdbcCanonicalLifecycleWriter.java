package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdReservation;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteResult;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.artifact.lifecycle.RecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.ValidityDecision;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SQLite canonical confirmation transaction for active record lifecycles.
 *
 * <p>Never-reusable public and lifecycle identities are reserved before the
 * canonical transaction. The transaction then serializes with expiration,
 * samples one effective time, and atomically commits rows, provenance,
 * receipt staging, observation idempotency, revision and projection work.
 */
public final class JdbcCanonicalLifecycleWriter implements CanonicalArtifactWriter {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final Map<String, JdbcArtifactIdAllocator> publicIdAllocators;
    private final JdbcLifecycleIdAllocator lifecycleIdAllocator;
    private final JdbcLifecycleArchive lifecycleArchive;
    private final JdbcConfirmationReceiptWriter receiptWriter;
    private final ConnectionTimeSource timeSource;
    private final RecordValidityPolicy validityPolicy;
    private final JdbcLifecycleTransactionObserver transactionObserver;

    /** Creates and validates the lifecycle writer's durable ID allocators. */
    public JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                        LifecycleTimeSource timeSource,
                                        RecordValidityPolicy validityPolicy,
                                        java.time.Clock allocatorClock) {
        this(dataSource, schemas, publicIdDefinitions, timeSource, validityPolicy,
                allocatorClock, JdbcLifecycleTransactionObserver.NOOP);
    }

    /** Creates a writer that advances clock high-water in the canonical transaction. */
    public JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                        JdbcLifecycleClock timeSource,
                                        RecordValidityPolicy validityPolicy,
                                        java.time.Clock allocatorClock) {
        this(dataSource, schemas, publicIdDefinitions,
                (ConnectionTimeSource) Objects.requireNonNull(timeSource, "timeSource")::now,
                validityPolicy, allocatorClock, JdbcLifecycleTransactionObserver.NOOP);
    }

    JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                 List<DataframeArtifactSchema> schemas,
                                 List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                 LifecycleTimeSource timeSource,
                                 RecordValidityPolicy validityPolicy,
                                 java.time.Clock allocatorClock,
                                 JdbcLifecycleTransactionObserver transactionObserver) {
        this(dataSource, schemas, publicIdDefinitions,
                (ConnectionTimeSource) ignored -> Objects.requireNonNull(timeSource, "timeSource").now(),
                validityPolicy, allocatorClock, transactionObserver);
    }

    private JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                         List<DataframeArtifactSchema> schemas,
                                         List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                         ConnectionTimeSource timeSource,
                                         RecordValidityPolicy validityPolicy,
                                         java.time.Clock allocatorClock,
                                         JdbcLifecycleTransactionObserver transactionObserver) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.validityPolicy = Objects.requireNonNull(validityPolicy, "validityPolicy");
        this.transactionObserver = Objects.requireNonNull(transactionObserver, "transactionObserver");
        Objects.requireNonNull(allocatorClock, "allocatorClock");
        this.lifecycleIdAllocator = new JdbcLifecycleIdAllocator(dataSource, allocatorClock);
        this.lifecycleArchive = new JdbcLifecycleArchive();
        this.receiptWriter = new JdbcConfirmationReceiptWriter(this.schemas);
        this.publicIdAllocators = initializePublicIdAllocators(publicIdDefinitions, allocatorClock);
    }

    @Override
    public LifecycleWriteResult confirm(CanonicalArtifactConfirmation confirmation) {
        Objects.requireNonNull(confirmation, "confirmation");
        DataframeArtifactSchema schema = requireCommand(confirmation);
        Optional<LifecycleWriteResult> prior = loadCommitted(confirmation);
        if (prior.isPresent()) {
            return prior.orElseThrow();
        }

        ReservedIds ids = reserveWorstCase(schema, confirmation.records());
        try (Connection connection = dataSource.getConnection()) {
            return confirm(connection, schema, confirmation, ids);
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException(
                    "Failed lifecycle confirmation for artifact: " + confirmation.artifactName(), e);
        }
    }

    private LifecycleWriteResult confirm(Connection connection,
                                         DataframeArtifactSchema schema,
                                         CanonicalArtifactConfirmation confirmation,
                                         ReservedIds ids) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            JdbcLifecycleTransactions.acquireActiveWriteOwnership(
                    connection,
                    confirmation.artifactName(),
                    JdbcLifecycleTransactionObserver.Operation.CONFIRM,
                    transactionObserver);
            Optional<LifecycleWriteResult> raced = loadCommitted(connection, confirmation);
            if (raced.isPresent()) {
                connection.commit();
                return raced.orElseThrow();
            }

            EffectiveTime asOf = Objects.requireNonNull(
                    timeSource.now(connection), "lifecycle effective time");
            ValidityDecision validity = validityPolicy.decide(asOf).requireValidAt(asOf);
            ensureObservation(connection, confirmation, asOf);

            int created = 0;
            int renewed = 0;
            int restarted = 0;
            int publicOffset = 0;
            int lifecycleOffset = 0;
            for (CanonicalRecordConfirmation record : confirmation.records()) {
                Optional<StoredLifecycle> stored = findStored(connection, schema, record.rowKey().value());
                if (stored.isEmpty()) {
                    insertActive(connection, schema, confirmation.sourceKey(), record,
                            ids.publicId(publicOffset, record), ids.lifecycleIds().idAt(lifecycleOffset),
                            asOf, validity);
                    publicOffset += publicIdIncrement(record);
                    lifecycleOffset++;
                    created++;
                } else if (stored.orElseThrow().validUntilEpochMs() > epochMillis(asOf)) {
                    renewActive(connection, schema, confirmation.sourceKey(), stored.orElseThrow(), asOf, validity);
                    renewed++;
                } else {
                    lifecycleArchive.archiveAndDelete(connection, schema, stored.orElseThrow().rowId(), asOf);
                    insertActive(connection, schema, confirmation.sourceKey(), record,
                            ids.publicId(publicOffset, record), ids.lifecycleIds().idAt(lifecycleOffset),
                            asOf, validity);
                    publicOffset += publicIdIncrement(record);
                    lifecycleOffset++;
                    restarted++;
                }
            }

            int newPublicRows = Math.addExact(created, restarted);
            long revision = newPublicRows == 0
                    ? currentRevision(connection, schema.artifactName())
                    : bumpRevision(connection, schema.artifactName(), asOf.value().toString());
            ProjectionGeneration generation = newPublicRows == 0
                    ? currentProjectionGeneration(connection, schema.artifactName())
                    : advanceProjectionGeneration(connection, schema.artifactName(), asOf);

            insertCommitMarker(connection, confirmation, asOf, created, renewed, restarted, revision, generation);
            receiptWriter.stageAndPublishIfComplete(connection, schema, confirmation, asOf);
            connection.commit();
            return new LifecycleWriteResult(
                    confirmation.observationId(), confirmation.artifactName(), asOf,
                    created, renewed, restarted, revision, generation, false);
        } catch (SQLException | RuntimeException e) {
            failure = e;
            JdbcLifecycleTransactions.rollback(connection, e);
            throw e;
        } finally {
            JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
        }
    }

    private DataframeArtifactSchema requireCommand(CanonicalArtifactConfirmation confirmation) {
        DataframeArtifactSchema schema = schemas.get(confirmation.artifactName());
        if (schema == null) {
            throw new IocExtractorException("Unknown dataframe artifact: " + confirmation.artifactName());
        }
        List<String> expectedHeader = publicHeader(schema);
        if (!expectedHeader.equals(confirmation.header())) {
            throw new IllegalArgumentException("Canonical confirmation header does not match artifact schema: "
                    + confirmation.artifactName());
        }
        boolean hasPublicId = expectedHeader.contains("id");
        for (CanonicalRecordConfirmation record : confirmation.records()) {
            Optional<String> idColumn = record.preparedRow().idColumn();
            boolean validIdSlot = hasPublicId
                    ? idColumn.filter("id"::equals).isPresent()
                    : idColumn.isEmpty();
            if (!validIdSlot) {
                throw new IllegalArgumentException(
                        "Prepared public-id slot does not match artifact schema: " + confirmation.artifactName());
            }
            if (idColumn.isPresent()) {
                String supplied = record.preparedRow().template().value(idColumn.orElseThrow());
                if (supplied != null && !supplied.isBlank()) {
                    throw new IllegalArgumentException("Service-owned public id must remain deferred");
                }
            }
        }
        return schema;
    }

    private ReservedIds reserveWorstCase(DataframeArtifactSchema schema,
                                         List<CanonicalRecordConfirmation> records) {
        int publicCount = Math.toIntExact(records.stream()
                .filter(record -> record.preparedRow().idColumn().isPresent())
                .count());
        ArtifactIdReservation publicIds = null;
        if (publicCount > 0) {
            JdbcArtifactIdAllocator allocator = publicIdAllocators.get(schema.artifactName());
            if (allocator == null) {
                throw new IocExtractorException(
                        "Public id allocator is not configured for artifact: " + schema.artifactName());
            }
            publicIds = allocator.reserve(schema.artifactName(), publicCount);
        }
        return new ReservedIds(publicIds, lifecycleIdAllocator.reserve(records.size()));
    }

    private Optional<LifecycleWriteResult> loadCommitted(CanonicalArtifactConfirmation confirmation) {
        try (Connection connection = dataSource.getConnection()) {
            return loadCommitted(connection, confirmation);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to inspect canonical observation commit", e);
        }
    }

    private Optional<LifecycleWriteResult> loadCommitted(Connection connection,
                                                         CanonicalArtifactConfirmation confirmation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.effective_as_of_ms, c.inserted, c.renewed, c.restarted,
                       c.artifact_revision, c.projection_generation, o.source_key
                FROM canonical_observation_commit c
                JOIN canonical_observation o ON o.observation_id = c.observation_id
                WHERE c.observation_id = ? AND c.artifact = ?
                """)) {
            statement.setString(1, confirmation.observationId().value());
            statement.setString(2, confirmation.artifactName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                if (!confirmation.sourceKey().equals(resultSet.getString("source_key"))) {
                    throw new IocExtractorException("Observation id was already used for another source");
                }
                return Optional.of(new LifecycleWriteResult(
                        confirmation.observationId(),
                        confirmation.artifactName(),
                        EffectiveTime.at(Instant.ofEpochMilli(resultSet.getLong("effective_as_of_ms"))),
                        resultSet.getInt("inserted"),
                        resultSet.getInt("renewed"),
                        resultSet.getInt("restarted"),
                        resultSet.getLong("artifact_revision"),
                        new ProjectionGeneration(resultSet.getLong("projection_generation")),
                        true));
            }
        }
    }

    private void ensureObservation(Connection connection,
                                   CanonicalArtifactConfirmation confirmation,
                                   EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO canonical_observation(observation_id, source_key, state, started_at_ms)
                VALUES (?, ?, 'OPEN', ?)
                ON CONFLICT(observation_id) DO NOTHING
                """)) {
            statement.setString(1, confirmation.observationId().value());
            statement.setString(2, confirmation.sourceKey());
            statement.setLong(3, epochMillis(asOf));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_key, state
                FROM canonical_observation
                WHERE observation_id = ?
                """)) {
            statement.setString(1, confirmation.observationId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !confirmation.sourceKey().equals(resultSet.getString("source_key"))
                        || !"OPEN".equals(resultSet.getString("state"))) {
                    throw new IocExtractorException("Canonical observation identity is not writable");
                }
            }
        }
    }

    private Optional<StoredLifecycle> findStored(Connection connection,
                                                 DataframeArtifactSchema schema,
                                                 String rowKey) throws SQLException {
        String sql = "SELECT " + quote("id") + ", " + quote("_lifecycle_id") + ", "
                + quote("_first_confirmed_at_epoch_ms") + ", "
                + quote("_last_confirmed_at_epoch_ms") + ", "
                + quote("_valid_until_epoch_ms") + " FROM " + quote(schema.artifactName())
                + " WHERE " + quote("row_key") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rowKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                long lifecycleId = resultSet.getLong("_lifecycle_id");
                if (resultSet.wasNull()) {
                    throw new IocExtractorException(
                            "Active lifecycle row is missing required metadata: " + schema.artifactName());
                }
                long firstConfirmed = resultSet.getLong("_first_confirmed_at_epoch_ms");
                if (resultSet.wasNull()) {
                    throw new IocExtractorException(
                            "Active lifecycle row is missing required metadata: " + schema.artifactName());
                }
                long lastConfirmed = resultSet.getLong("_last_confirmed_at_epoch_ms");
                if (resultSet.wasNull()) {
                    throw new IocExtractorException(
                            "Active lifecycle row is missing required metadata: " + schema.artifactName());
                }
                long validUntil = resultSet.getLong("_valid_until_epoch_ms");
                if (resultSet.wasNull()) {
                    throw new IocExtractorException(
                            "Active lifecycle row is missing required metadata: " + schema.artifactName());
                }
                if (lifecycleId <= 0 || firstConfirmed > lastConfirmed || lastConfirmed >= validUntil) {
                    throw new IocExtractorException(
                            "Active lifecycle row has invalid ordered metadata: " + schema.artifactName());
                }
                return Optional.of(new StoredLifecycle(
                        resultSet.getLong("id"), lifecycleId, validUntil));
            }
        }
    }

    private void insertActive(Connection connection,
                              DataframeArtifactSchema schema,
                              String sourceKey,
                              CanonicalRecordConfirmation confirmation,
                              Long publicId,
                              com.iocextractor.application.artifact.lifecycle.LifecycleId lifecycleId,
                              EffectiveTime asOf,
                              ValidityDecision validity) throws SQLException {
        ArtifactRow row = confirmation.preparedRow().idColumn().isPresent()
                ? confirmation.preparedRow().materialize(publicId)
                : confirmation.preparedRow().template();
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (DataframeColumn column : schema.columns()) {
            columns.add(column.name());
            values.add(row.value(column.name()));
        }
        columns.add("row_key");
        values.add(confirmation.rowKey().value());
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
        long rowId = requireRowId(connection, schema.artifactName(), confirmation.rowKey().value());
        upsertSource(connection, schema.artifactName(), rowId, sourceKey, asOf.value().toString());
    }

    private void renewActive(Connection connection,
                             DataframeArtifactSchema schema,
                             String sourceKey,
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
        upsertSource(connection, schema.artifactName(), stored.rowId(), sourceKey, asOf.value().toString());
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

    private void insertCommitMarker(Connection connection,
                                    CanonicalArtifactConfirmation confirmation,
                                    EffectiveTime asOf,
                                    int created,
                                    int renewed,
                                    int restarted,
                                    long revision,
                                    ProjectionGeneration generation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO canonical_observation_commit(
                    observation_id, artifact, committed_at_ms, effective_as_of_ms,
                    inserted, renewed, restarted, artifact_revision, projection_generation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, confirmation.observationId().value());
            statement.setString(2, confirmation.artifactName());
            statement.setLong(3, epochMillis(asOf));
            statement.setLong(4, epochMillis(asOf));
            statement.setInt(5, created);
            statement.setInt(6, renewed);
            statement.setInt(7, restarted);
            statement.setLong(8, revision);
            statement.setLong(9, generation.value());
            statement.executeUpdate();
        }
    }

    private long bumpRevision(Connection connection, String artifact, String changedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO artifact_revision(artifact, revision, changed_at)
                VALUES (?, 1, ?)
                ON CONFLICT(artifact) DO UPDATE SET
                    revision = artifact_revision.revision + 1,
                    changed_at = excluded.changed_at
                """)) {
            statement.setString(1, artifact);
            statement.setString(2, changedAt);
            statement.executeUpdate();
        }
        return currentRevision(connection, artifact);
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

    private ProjectionGeneration advanceProjectionGeneration(Connection connection,
                                                             String artifact,
                                                             EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO artifact_projection_state(
                    artifact, required_generation, projected_generation, requested_at_ms)
                VALUES (?, 1, 0, ?)
                ON CONFLICT(artifact) DO UPDATE SET
                    required_generation = artifact_projection_state.required_generation + 1,
                    requested_at_ms = excluded.requested_at_ms,
                    last_error_code = NULL
                """)) {
            statement.setString(1, artifact);
            statement.setLong(2, epochMillis(asOf));
            statement.executeUpdate();
        }
        return currentProjectionGeneration(connection, artifact);
    }

    private ProjectionGeneration currentProjectionGeneration(Connection connection, String artifact)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT required_generation FROM artifact_projection_state WHERE artifact = ?")) {
            statement.setString(1, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                return new ProjectionGeneration(resultSet.next() ? resultSet.getLong(1) : 0L);
            }
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

    private Map<String, JdbcArtifactIdAllocator> initializePublicIdAllocators(
            List<ArtifactIdAllocatorDefinition> definitions,
            java.time.Clock allocatorClock) {
        Objects.requireNonNull(definitions, "publicIdDefinitions");
        Map<String, JdbcArtifactIdAllocator> allocators = new LinkedHashMap<>();
        for (ArtifactIdAllocatorDefinition definition : definitions) {
            Objects.requireNonNull(definition, "publicIdDefinitions element");
            DataframeArtifactSchema schema = schemas.get(definition.artifact());
            if (schema == null || !publicHeader(schema).contains("id")) {
                throw new IllegalArgumentException(
                        "Public id allocator does not match an id-bearing artifact: " + definition.artifact());
            }
            JdbcArtifactIdAllocator allocator = new JdbcArtifactIdAllocator(dataSource, allocatorClock);
            allocator.ensureInitialized(definition);
            if (allocators.put(definition.artifact(), allocator) != null) {
                throw new IllegalArgumentException(
                        "Duplicate public id allocator definition: " + definition.artifact());
            }
        }
        return Map.copyOf(allocators);
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

    private int publicIdIncrement(CanonicalRecordConfirmation record) {
        return record.preparedRow().idColumn().isPresent() ? 1 : 0;
    }

    private long epochMillis(EffectiveTime time) {
        return time.value().toEpochMilli();
    }

    @FunctionalInterface
    private interface ConnectionTimeSource {
        EffectiveTime now(Connection connection) throws SQLException;
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

    private record StoredLifecycle(long rowId, long lifecycleId, long validUntilEpochMs) {
    }

    private record ReservedIds(ArtifactIdReservation publicIds, LifecycleIdReservation lifecycleIds) {

        private ReservedIds {
            Objects.requireNonNull(lifecycleIds, "lifecycleIds");
        }

        private Long publicId(int offset, CanonicalRecordConfirmation record) {
            if (record.preparedRow().idColumn().isEmpty()) {
                return null;
            }
            if (publicIds == null) {
                throw new IllegalStateException("Public id reservation is missing");
            }
            return publicIds.idAt(offset);
        }
    }
}
