package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.common.IocExtractorException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Isolated candidate composition around the existing native kernel; default bootstrap stays native. */
final class JdbcStagedCanonicalBackend {
    private final JdbcStagedCanonicalConfiguration configuration;
    private final JdbcStagedPublication publication;
    private final JdbcPrivateIdReservations internalIds;
    private final JdbcTypedPublicationStore store;

    private JdbcStagedCanonicalBackend(JdbcStagedCanonicalConfiguration configuration, JdbcTypedPublicationStore store,
                                       JdbcPublicationObserver observer) {
        this.configuration = configuration;
        this.store = store;
        publication = new JdbcStagedPublication(new JdbcStagedPublication.Environment(configuration.dataSource(), store,
                configuration.clock(), configuration.validity(), configuration.allocatorClock(), configuration.admission(),
                configuration.workspaces(), configuration.schemas()), observer);
        internalIds = new JdbcPrivateIdReservations(configuration.dataSource(), configuration.allocatorClock(), configuration.admission());
    }

    static JdbcStagedCanonicalBackend createEmpty(JdbcStagedCanonicalConfiguration configuration, JdbcPublicationObserver observer) {
        try {
            try (var connection = configuration.dataSource().getConnection()) {
                JdbcPublicationCatalog.requireEmpty(connection, JdbcPublicationCatalog.inspectNative(connection, configuration.schemas()));
            }
            initializeIds(configuration);
            var transactions = new JdbcPublicationTransactions(configuration.dataSource(), configuration.admission());
            var store = transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL,
                    c -> {
                        var candidate = JdbcTypedPublicationStore.initializeEmpty(c, configuration.schemas());
                        JdbcPublicationTable.execute(c, "CREATE TABLE publication_configuration(singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),pins TEXT NOT NULL)");
                        try (var statement = c.prepareStatement("INSERT INTO publication_configuration VALUES(1,?)")) {
                            statement.setString(1, configuration.fingerprint()); statement.executeUpdate();
                        }
                        return candidate;
                    });
            return new JdbcStagedCanonicalBackend(configuration, store, observer);
        } catch (SQLException failure) { throw new IocExtractorException("Cannot initialize isolated staged canonical backend", failure); }
    }

    static JdbcStagedCanonicalBackend reopen(JdbcStagedCanonicalConfiguration configuration, JdbcPublicationObserver observer) {
        try (var connection = configuration.dataSource().getConnection()) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pins FROM publication_configuration WHERE singleton_id=1")) {
                if (!rows.next() || !configuration.fingerprint().equals(rows.getString(1))) {
                    throw new SQLException("Staged canonical configuration changed; drained migration required");
                }
            }
            return new JdbcStagedCanonicalBackend(configuration,
                    JdbcTypedPublicationStore.reopen(connection, configuration.schemas()), observer);
        } catch (SQLException failure) { throw new IocExtractorException("Cannot reopen staged canonical backend", failure); }
    }

    private static void initializeIds(JdbcStagedCanonicalConfiguration configuration) {
        var publicIds = new JdbcArtifactIdAllocator(configuration.dataSource(), configuration.allocatorClock());
        configuration.publicIds().forEach(publicIds::ensureInitialized);
        var internal = new JdbcPrivateIdReservations(configuration.dataSource(), configuration.allocatorClock(), configuration.admission());
        for (var schema : configuration.schemas()) {
            long epoch = configuration.identities().stream().filter(identity -> identity.artifactName().equals(schema.artifactName()))
                    .mapToLong(ArtifactIdentityDefinition::epoch).findFirst().orElseThrow();
            if (schema.columns().stream().noneMatch(column -> column.name().equals("id"))) { internal.initialize(schema.artifactName(), epoch); }
            internal.initializeHistory(schema.artifactName(), epoch);
        }
    }

    void reservePrivateIds(DataSource working, Map<String, Integer> counts) throws SQLException {
        try (Connection connection = working.getConnection()) {
            connection.setAutoCommit(false);
            try {
            for (var entry : counts.entrySet()) {
                if (entry.getValue() == 0) { continue; }
                var schema = configuration.schemas().stream().filter(candidate -> candidate.artifactName().equals(entry.getKey()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown staged artifact"));
                if (schema.columns().stream().noneMatch(column -> column.name().equals("id"))) {
                    JdbcPrivateIdReservations.seedPrivateSequence(connection, entry.getKey(), internalIds.reserve(entry.getKey(), entry.getValue()));
                }
                JdbcPrivateIdReservations.seedPrivateSequence(connection, entry.getKey() + "_history",
                        internalIds.reserve(entry.getKey() + "_history", entry.getValue()));
            }
            connection.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                JdbcLifecycleTransactions.rollback(connection, failure); throw failure;
            }
        }
    }

    JdbcCanonicalLifecycleWriter nativeConfirmation(DataSource dataSource, EffectiveTime asOf, JdbcCanonicalPreparationContext context) {
        return new JdbcCanonicalLifecycleWriter(dataSource, configuration.schemas(), configuration.publicIds(),
                ignored -> asOf, configuration.validity(), JdbcLifecycleTransactionObserver.NOOP,
                configuration.identities(), new JdbcWriterAdmission(), context);
    }

    JdbcCanonicalImportWriter nativeImport(DataSource dataSource, EffectiveTime asOf, JdbcCanonicalPreparationContext context) {
        return new JdbcCanonicalImportWriter(dataSource, configuration.schemas(), configuration.publicIds(), configuration.identities(),
                configuration.importWorkspaceRoot(), ignored -> asOf, configuration.validity(), new JdbcWriterAdmission(),
                JdbcCanonicalImportObserver.NOOP, configuration.importReceiptRetention(), configuration.policies(), context);
    }

    JdbcCanonicalPreparationContext readContext() {
        return new JdbcCanonicalPreparationContext(new JdbcCanonicalReservationAuthority(configuration.dataSource(),
                configuration.allocatorClock(), configuration.admission()));
    }

    JdbcStagedCanonicalConfiguration configuration() { return configuration; }
    JdbcTypedPublicationStore store() { return store; }
    JdbcStagedPublication publication() { return publication; }
    JdbcStagedConfirmationWriter confirmations() { return new JdbcStagedConfirmationWriter(this); }
    JdbcStagedImportWriter imports() { return new JdbcStagedImportWriter(this); }
    JdbcActiveArtifactReader activeRows() { return new JdbcActiveArtifactReader(configuration.dataSource(), configuration.schemas()); }
    JdbcCanonicalMatchPlanner matching() { return new JdbcCanonicalMatchPlanner(configuration.dataSource(), configuration.schemas()); }
    com.iocextractor.application.port.out.artifact.CanonicalArtifactStreamReader streamReader() {
        return new JdbcCanonicalArtifactRepository(configuration.dataSource(), configuration.schemas(),
                new com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver(configuration.identities()),
                configuration.allocatorClock(), configuration.clock(), configuration.admission());
    }
    JdbcStagedExpiryStore expiry() { return new JdbcStagedExpiryStore(this); }
    JdbcStagedHistoryStore history() { return new JdbcStagedHistoryStore(this); }
    JdbcStagedProjectionStore projections() { return new JdbcStagedProjectionStore(this); }
    JdbcStagedImportEvidenceStore importEvidence() { return new JdbcStagedImportEvidenceStore(this); }
    JdbcStagedReceiptStore receipts(java.time.Duration stagingRetention) { return new JdbcStagedReceiptStore(this, stagingRetention); }
    JdbcPublicationVersionCleanup cleanup() { return new JdbcPublicationVersionCleanup(this); }
    JdbcSnapshotSliceReader snapshots() {
        return new JdbcSnapshotSliceReader(configuration.dataSource(), configuration.schemas(), configuration.allocatorClock(),
                configuration.clock(), com.iocextractor.diagnostics.sink.NoopDiagnosticSink.INSTANCE,
                new com.iocextractor.diagnostics.DiagnosticFactory(configuration.allocatorClock()), configuration.admission(), 2,
                new JdbcStagedSlotReconciliation(this));
    }
    void requireArtifact(String artifact) {
        if (!store.artifacts().contains(artifact)) { throw new IllegalArgumentException("Unknown staged artifact: " + artifact); }
    }
}
