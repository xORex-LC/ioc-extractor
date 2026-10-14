package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleId;
import com.iocextractor.application.artifact.lifecycle.ValidityDecision;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Immutable mutation catalog shared by ingest and import. Each transaction opens
 * its own thread-confined session; no JDBC resource is retained by this factory.
 */
public final class JdbcCanonicalMutationEngine {

    private final CanonicalArtifactKeyResolver keyResolver;
    private final JdbcCanonicalMatchPlanner matchPlanner;
    private final JdbcLifecycleArchive lifecycleArchive;
    private final JdbcOrderedFieldStore orderedFields;

    /** Creates a kernel for one immutable schema and key catalog. */
    public JdbcCanonicalMutationEngine(javax.sql.DataSource dataSource,
                                       List<DataframeArtifactSchema> schemas,
                                       List<ArtifactIdentityDefinition> definitions) {
        this.keyResolver = new CanonicalArtifactKeyResolver(definitions);
        this.matchPlanner = new JdbcCanonicalMatchPlanner(dataSource, schemas);
        this.lifecycleArchive = new JdbcLifecycleArchive();
        this.orderedFields = new JdbcOrderedFieldStore();
    }

    /** Validates one ordered observation against the dataframe-owned authority. */
    void validateRegistration(Connection connection, RegisteredObservation registration) throws SQLException {
        orderedFields.validateRegistration(connection, registration);
    }

    JdbcCanonicalMutationSession openSession(Connection connection, DataframeArtifactSchema schema,
                                             EffectiveTime asOf, ValidityDecision validity) throws SQLException {
        return new JdbcCanonicalMutationSession(connection, schema, asOf, validity,
                keyResolver, matchPlanner, lifecycleArchive, orderedFields);
    }

    /** Applies one pre-resolved patch in the caller's transaction. */
    public CanonicalRecordMutationOutcome mutateExisting(Connection connection,
                                                         DataframeArtifactSchema schema,
                                                         long canonicalRowId,
                                                         ArtifactRow finalRow,
                                                         boolean renewTtl,
                                                         EffectiveTime asOf,
                                                         ValidityDecision validity) throws SQLException {
        try (var session = openSession(connection, schema, asOf, validity)) {
            return session.mutateExisting(canonicalRowId, finalRow, renewTtl, null);
        }
    }

    /** Inserts one pre-planned branch in the caller's transaction. */
    CanonicalRecordMutationOutcome insertPlanned(Connection connection,
                                                 DataframeArtifactSchema schema,
                                                 String sourceKey,
                                                 ArtifactRow incoming,
                                                 ArtifactRowKey recordKey,
                                                 LifecycleId lifecycleId,
                                                 EffectiveTime asOf,
                                                 ValidityDecision validity) throws SQLException {
        try (var session = openSession(connection, schema, asOf, validity)) {
            return session.insertPlanned(sourceKey, incoming, recordKey, lifecycleId);
        }
    }

    /** Loads one active-match candidate for import merge planning before mutation. */
    StoredLifecycle loadForImport(Connection connection, DataframeArtifactSchema schema, long canonicalRowId) {
        var reader = new JdbcCanonicalStoredRowReader(schema);
        try (var statement = connection.prepareStatement(reader.sql())) {
            return reader.load(statement, canonicalRowId);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to load canonical lifecycle", e);
        }
    }

    record StoredLifecycle(long rowId, long lifecycleId, long validUntilEpochMs,
                           ArtifactRowKey rowKey, ArtifactRow publicRow) {
    }
}
