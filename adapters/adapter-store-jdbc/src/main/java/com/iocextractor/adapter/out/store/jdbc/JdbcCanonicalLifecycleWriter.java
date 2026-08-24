package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdReservation;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalRecordMutationKind;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    private final JdbcCanonicalMutationEngine mutationEngine;
    private final JdbcConfirmationReceiptWriter receiptWriter;
    private final ConnectionTimeSource timeSource;
    private final RecordValidityPolicy validityPolicy;
    private final JdbcLifecycleTransactionObserver transactionObserver;
    private final JdbcWriterAdmission writerAdmission;

    /** Creates and validates the lifecycle writer's durable ID allocators. */
    public JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                        LifecycleTimeSource timeSource,
                                        RecordValidityPolicy validityPolicy,
                                        java.time.Clock allocatorClock) {
        this(dataSource, schemas, publicIdDefinitions,
                (ConnectionTimeSource) ignored -> Objects.requireNonNull(timeSource, "timeSource").now(),
                validityPolicy, allocatorClock, JdbcLifecycleTransactionObserver.NOOP, List.of(),
                new JdbcWriterAdmission());
    }

    /** Creates a testable writer with an explicit lifecycle time source and key catalog. */
    public JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                        LifecycleTimeSource timeSource,
                                        RecordValidityPolicy validityPolicy,
                                        java.time.Clock allocatorClock,
                                        List<ArtifactIdentityDefinition> identityDefinitions) {
        this(dataSource, schemas, publicIdDefinitions,
                (ConnectionTimeSource) ignored -> Objects.requireNonNull(timeSource, "timeSource").now(),
                validityPolicy, allocatorClock, JdbcLifecycleTransactionObserver.NOOP,
                identityDefinitions, new JdbcWriterAdmission());
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
                validityPolicy, allocatorClock, JdbcLifecycleTransactionObserver.NOOP, List.of(),
                new JdbcWriterAdmission());
    }

    /** Creates a writer backed by the shared versioned match and mutation kernel. */
    public JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                        JdbcLifecycleClock timeSource,
                                        RecordValidityPolicy validityPolicy,
                                        java.time.Clock allocatorClock,
                                        List<ArtifactIdentityDefinition> identityDefinitions) {
        this(dataSource, schemas, publicIdDefinitions,
                (ConnectionTimeSource) Objects.requireNonNull(timeSource, "timeSource")::now,
                validityPolicy, allocatorClock, JdbcLifecycleTransactionObserver.NOOP,
                identityDefinitions, new JdbcWriterAdmission());
    }

    /** Creates a writer participating in one composition-root writer admission. */
    public JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                        JdbcLifecycleClock timeSource,
                                        RecordValidityPolicy validityPolicy,
                                        java.time.Clock allocatorClock,
                                        List<ArtifactIdentityDefinition> identityDefinitions,
                                        JdbcWriterAdmission writerAdmission) {
        this(dataSource, schemas, publicIdDefinitions,
                (ConnectionTimeSource) Objects.requireNonNull(timeSource, "timeSource")::now,
                validityPolicy, allocatorClock, JdbcLifecycleTransactionObserver.NOOP,
                identityDefinitions, writerAdmission);
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
                validityPolicy, allocatorClock, transactionObserver, List.of(),
                new JdbcWriterAdmission());
    }

    private JdbcCanonicalLifecycleWriter(DataSource dataSource,
                                         List<DataframeArtifactSchema> schemas,
                                         List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
                                         ConnectionTimeSource timeSource,
                                         RecordValidityPolicy validityPolicy,
                                         java.time.Clock allocatorClock,
                                         JdbcLifecycleTransactionObserver transactionObserver,
                                         List<ArtifactIdentityDefinition> identityDefinitions,
                                         JdbcWriterAdmission writerAdmission) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.validityPolicy = Objects.requireNonNull(validityPolicy, "validityPolicy");
        this.transactionObserver = Objects.requireNonNull(transactionObserver, "transactionObserver");
        this.writerAdmission = Objects.requireNonNull(writerAdmission, "writerAdmission");
        Objects.requireNonNull(allocatorClock, "allocatorClock");
        this.lifecycleIdAllocator = new JdbcLifecycleIdAllocator(dataSource, allocatorClock);
        this.mutationEngine = new JdbcCanonicalMutationEngine(dataSource, schemas, identityDefinitions);
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
        return writerAdmission.execute(() -> confirmAdmitted(schema, confirmation, ids));
    }

    private LifecycleWriteResult confirmAdmitted(DataframeArtifactSchema schema,
                                                  CanonicalArtifactConfirmation confirmation,
                                                  ReservedIds ids) {
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
                var outcome = mutationEngine.confirm(
                        connection, schema, confirmation.sourceKey(), record,
                        ids.publicId(publicOffset, record), ids.lifecycleIds().idAt(lifecycleOffset),
                        asOf, validity);
                if (outcome.kind() == CanonicalRecordMutationKind.INSERTED) {
                    publicOffset += publicIdIncrement(record);
                    lifecycleOffset++;
                    created++;
                } else if (outcome.kind() == CanonicalRecordMutationKind.TTL_CONFIRMED) {
                    renewed++;
                } else if (outcome.kind() == CanonicalRecordMutationKind.RESTARTED) {
                    publicOffset += publicIdIncrement(record);
                    lifecycleOffset++;
                    restarted++;
                } else {
                    throw new IocExtractorException("Unexpected ordinary-ingest mutation outcome: "
                            + outcome.kind());
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
