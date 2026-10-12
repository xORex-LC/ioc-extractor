package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;
import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;

/** Candidate physical catalog. Initialization is explicit and refuses migration or mixed native writers. */
final class JdbcTypedPublicationStore {
    private final List<JdbcPublicationTable> tables;
    private final Set<String> artifacts;
    private final JdbcTypedJournal journal;
    private final JdbcReceiptPayloadLayout receiptPayloads;
    private final boolean retiredIdentities;

    JdbcTypedPublicationStore(List<JdbcPublicationTable> tables, Set<String> artifacts) {
        this(tables, artifacts, JdbcTypedJournal.ROW_POINTER, JdbcReceiptPayloadLayout.VERSIONED, false);
    }

    private JdbcTypedPublicationStore(List<JdbcPublicationTable> tables, Set<String> artifacts, JdbcTypedJournal journal,
                                      JdbcReceiptPayloadLayout receiptPayloads, boolean retiredIdentities) {
        this.tables = List.copyOf(tables);
        this.artifacts = Set.copyOf(artifacts);
        this.journal = journal;
        this.receiptPayloads = receiptPayloads;
        this.retiredIdentities = retiredIdentities;
    }

    static JdbcTypedPublicationStore initializeEmpty(Connection connection, List<DataframeArtifactSchema> schemas)
            throws SQLException {
        return initializeEmpty(connection, schemas, JdbcTypedJournal.ROW_POINTER);
    }

    static JdbcTypedPublicationStore initializeEmpty(Connection connection, List<DataframeArtifactSchema> schemas,
                                                     JdbcTypedJournal journal) throws SQLException {
        return initializeEmpty(connection, schemas, journal, JdbcReceiptPayloadLayout.VERSIONED);
    }

    static JdbcTypedPublicationStore initializeEmpty(Connection connection, List<DataframeArtifactSchema> schemas,
            JdbcTypedJournal journal, JdbcReceiptPayloadLayout receiptPayloads) throws SQLException {
        return initialize(connection, schemas, false, 0, journal, receiptPayloads);
    }

    /** Caller has stopped intake, drained coordination and owns the database lease and migration transaction. */
    static JdbcTypedPublicationStore migrateNative(Connection connection, List<DataframeArtifactSchema> schemas,
                                                   long migrationMillis) throws SQLException {
        return initialize(connection, schemas, true, migrationMillis, JdbcTypedJournal.ROW_POINTER, JdbcReceiptPayloadLayout.VERSIONED);
    }

    private static JdbcTypedPublicationStore initialize(Connection connection, List<DataframeArtifactSchema> schemas,
                                                       boolean populated, long migrationMillis, JdbcTypedJournal journal,
                                                       JdbcReceiptPayloadLayout receiptPayloads) throws SQLException {
        if (connection.getAutoCommit()) { throw new SQLException("Publication initialization requires an owned transaction"); }
        if (JdbcLifecycleTransactions.readActivationState(connection) != LifecycleActivationState.ACTIVE) {
            throw new SQLException("Staged publication requires active fixed lifecycle");
        }
        List<JdbcPublicationTable> tables = JdbcPublicationCatalog.inspectNative(connection, schemas);
        if (!populated) { JdbcPublicationCatalog.requireEmpty(connection, tables); }
        Set<String> artifacts = schemas.stream().map(DataframeArtifactSchema::artifactName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        new JdbcPublicationProtocol().initialize(connection);
        JdbcPublicationRetiredIdentity.initialize(connection);
        new JdbcPublicationOwner().initialize(connection);
        JdbcPublicationCleanupCursor.initialize(connection);
        JdbcPublicationCatalog.persist(connection, tables);
        journal.persist(connection);
        receiptPayloads.persist(connection);
        for (var table : tables) {
            if (receiptPayloads.contains(table, artifacts)) { JdbcImmutableReceiptPayload.create(connection, table); }
            else { JdbcTypedVersionTable.create(connection, table, artifacts.contains(table.name())); }
        }
        if (populated) { JdbcStagedCanonicalMigration.copy(connection, tables, artifacts, migrationMillis); }
        for (var table : tables.reversed()) { execute(connection, "DROP TABLE " + quote(table.name())); }
        for (var table : tables) {
            if (receiptPayloads.contains(table, artifacts)) { JdbcImmutableReceiptPayload.createView(connection, table); }
            else { JdbcTypedVersionTable.createView(connection, table, artifacts.contains(table.name())); }
        }
        return new JdbcTypedPublicationStore(tables, artifacts, journal, receiptPayloads, true);
    }

    static JdbcTypedPublicationStore reopen(Connection connection, List<DataframeArtifactSchema> schemas) throws SQLException {
        List<JdbcPublicationTable> tables = JdbcPublicationCatalog.load(connection);
        Set<String> artifacts = schemas.stream().map(DataframeArtifactSchema::artifactName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String artifact : artifacts) {
            if (tables.stream().noneMatch(table -> table.name().equals(artifact))) {
                throw new SQLException("Publication artifact catalog changed; drained migration required");
            }
        }
        return new JdbcTypedPublicationStore(tables, artifacts, JdbcTypedJournal.load(connection), JdbcReceiptPayloadLayout.load(connection),
                JdbcPublicationRetiredIdentity.supported(connection));
    }

    List<JdbcPublicationTable> tables() { return tables; }
    Set<String> artifacts() { return artifacts; }
    JdbcTypedJournal journal() { return journal; }
    JdbcReceiptPayloadLayout receiptPayloads() { return receiptPayloads; }
    boolean retiredIdentities() { return retiredIdentities; }
    String pins(String configuration) {
        String result = receiptPayloads.pins(journal.pins(configuration));
        return retiredIdentities ? "retired-identities-v1:" + result : result;
    }
}
