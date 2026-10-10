package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.dataframeimport.DataframeImportPromotionService;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportPromotionPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRequestedSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import static org.assertj.core.api.Assertions.assertThat;

/** File-backed native candidate shared by the owning test and its killable child JVM. */
final class StagedPublicationFixture implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final ImportDeliveryId DELIVERY = new ImportDeliveryId("fault-delivery");
    static final int ROWS = 80;
    final Path root;
    final HikariDataSource dataframe;
    final HikariDataSource service;
    final JdbcImportDeliveryLedger ledger;
    final JdbcStagedCanonicalBackend backend;
    final List<DataframeArtifactSchema> schemas = List.of(schema("masks", "mask"), schema("hashes", "hash"));
    final List<ArtifactIdentityDefinition> identities = List.of(identity("masks", "mask"), identity("hashes", "hash"));

    StagedPublicationFixture(Path root, boolean fresh, JdbcPublicationObserver observer) {
        this(root, fresh, observer, Map.of());
    }

    StagedPublicationFixture(Path root, boolean fresh, JdbcPublicationObserver observer,
                             Map<String, ArtifactWritePolicy> policies) {
        this(root, fresh, observer, policies, CLOCK);
    }

    StagedPublicationFixture(Path root, boolean fresh, JdbcPublicationObserver observer,
                             Map<String, ArtifactWritePolicy> policies, Clock wall) {
        this.root = root;
        dataframe = database(root.resolve("dataframe.db"));
        try {
            service = database(root.resolve("service.db"));
            try {
                if (fresh) {
                    new SqliteUserVersionSchemaMigrator(dataframe, DataframeFormatMigrations.sqlite()).migrate();
                    new SqliteUserVersionSchemaMigrator(service, ServiceSchemaMigrations.sqlite()).migrate();
                    new DataframeSchemaReconciler(dataframe).reconcile(schemas);
                    new JdbcArtifactIdentityStore(dataframe, CLOCK).ensureAll(identities);
                    var control = new JdbcLifecycleControlStore(dataframe, schemas);
                    var disabled = control.load();
                    var activating = disabled.beginActivation("fault-fixed-12h");
                    assertThat(control.compareAndSet(disabled, activating)).isTrue();
                    assertThat(control.compareAndSet(activating, activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();
                }
                var admission = new JdbcWriterAdmission();
                var workspaces = new JdbcDocumentPreparationWorkspaceFactory(root.resolve("private"),
                        new DocumentPreparationLimits(8 * 1024 * 1024, 64, 4096, 2048, 16 * 1024 * 1024, 256 * 1024 * 1024, 256),
                        new CanonicalArtifactIdentityResolver(identities), "fault-test");
                var configuration = new JdbcStagedCanonicalConfiguration(dataframe, schemas,
                        schemas.stream().map(s -> new ArtifactIdAllocatorDefinition(s.artifactName(), ArtifactIdStrategy.ASCENDING, 1, 1)).toList(),
                        identities, new JdbcLifecycleClock(dataframe, wall,
                        new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission), wall,
                        new FixedRecordValidityPolicy(Duration.ofHours(12)), admission, workspaces, root.resolve("stage"), policies, Duration.ofDays(90));
                backend = fresh ? JdbcStagedCanonicalBackend.createEmpty(configuration, observer)
                        : JdbcStagedCanonicalBackend.reopen(configuration, observer);
                ledger = new JdbcImportDeliveryLedger(service);
            } catch (RuntimeException | Error failure) { service.close(); throw failure; }
        } catch (RuntimeException | Error failure) { dataframe.close(); throw failure; }
    }

    void seed() {
        backend.confirmations().confirm(document("seed", "seed.example", "seed-source"));
    }

    CanonicalArtifactConfirmation document(String observation, String value, String source) {
        return document(observation, value, source, null);
    }

    CanonicalArtifactConfirmation document(String observation, String value, String source,
                                           RegisteredObservation registration) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("id", null); fields.put("mask", value); fields.put("source", source); fields.put("description", null);
        var positions = registration == null ? Map.<String, OccurrencePosition>of()
                : Map.of("source", new OccurrencePosition(0));
        var prepared = new PreparedArtifactRow(ArtifactRow.ordered(fields), Optional.of("id"), positions);
        var key = new CanonicalArtifactKeyResolver(identities).recordKeyOf("masks", prepared.template()).orElseThrow();
        return new CanonicalArtifactConfirmation(new ObservationId(observation), source,
                new ConfirmationReceiptContext(new ConfirmationReceiptId(observation + "-receipt"), "fault-policy", 1, Duration.ofDays(30)),
                "masks", schemas.getFirst().columns().stream().map(DataframeColumn::name).toList(),
                List.of(new CanonicalRecordConfirmation(new ArtifactRowKey(key.keyHash()), prepared)), registration);
    }

    CanonicalImportCommand stageAndRegister() {
        var snapshot = new ImportSnapshot(new ImportSnapshotReference("snapshot:fault"), new ImportSha256("a".repeat(64)), 100);
        var contract = new ImportContractPin(new ImportContractId("fault-contract"), 1, new ImportContractFingerprint("b".repeat(64)));
        var policy = new ImportPromotionPolicy(ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy("fault-profile", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        var create = new CreateImportWorkspaceCommand(DELIVERY, snapshot, contract, ImportDuplicatePolicy.COALESCE, policy);
        var limits = new ImportWorkspaceLimits(10_000, 8, 64, 10_000, 64L * 1024 * 1024,
                256L * 1024 * 1024, 192L * 1024 * 1024, 128L * 1024 * 1024, 100,
                new DelimitedInputLimits(10_000, 64, 64 * 1024, 8 * 1024 * 1024));
        var workspace = new JdbcImportWorkspace(root.resolve("stage"), limits, CLOCK);
        ImportStage stage;
        try (var writer = workspace.create(create)) {
            for (int index = 0; index < ROWS; index++) {
                writer.append(new ImportLogicalRow(index + 2, List.of(
                        branch("masks", "mask", "fault-" + index + ".example", ImportArtifactRole.PRIMARY, index + 1),
                        branch("hashes", "hash", "fault-hash-" + index, ImportArtifactRole.RELATED, 0))));
            }
            stage = writer.seal();
        }
        ledger.reserveClaim(new ImportClaimReservation(DELIVERY, new ImportSourceId("fault-feed"), "fault-candidate", NOW));
        transition(ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        transition(ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());
        transition(ImportDeliveryState.SNAPSHOT_PINNED, ImportDeliveryCheckpoint.snapshot(snapshot));
        transition(ImportDeliveryState.CONTRACT_PINNED, ImportDeliveryCheckpoint.contract(contract));
        transition(ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none());
        transition(ImportDeliveryState.STAGED, ImportDeliveryCheckpoint.stage(stage));
        var delivery = ledger.find(DELIVERY).orElseThrow();
        return new CanonicalImportCommand(DELIVERY, delivery.sequence(), delivery.sourceId(), snapshot, contract, stage);
    }

    private ImportArtifactBranch branch(String artifact, String carrier, String value, ImportArtifactRole role, long slot) {
        var values = new LinkedHashMap<String, ImportCell>();
        values.put(carrier, ImportCell.value(value)); values.put("source", ImportCell.value("fault-feed"));
        var row = ArtifactRow.ordered(Map.of(carrier, value));
        var resolver = new CanonicalArtifactKeyResolver(identities);
        return new ImportArtifactBranch(artifact, role, values, slot > 0 ? OptionalLong.of(slot) : OptionalLong.empty(), resolver.recordKeyOf(artifact, row),
                resolver.matchKeysOf(artifact, row));
    }

    private void transition(ImportDeliveryState next, ImportDeliveryCheckpoint checkpoint) {
        var delivery = ledger.find(DELIVERY).orElseThrow();
        assertThat(ledger.transition(new ImportDeliveryTransition(DELIVERY, delivery.state(), delivery.version(), next,
                Optional.empty(), checkpoint, Optional.empty(), NOW)).name()).isEqualTo("APPLIED");
    }

    void promoteHead() { new DataframeImportPromotionService(ledger, backend.imports(), CLOCK).processNext(); }

    List<ArtifactRow> snapshot() {
        var artifacts = new ArrayList<ExportArtifactSpec>();
        for (int index = 0; index < schemas.size(); index++) {
            var schema = schemas.get(index); var identity = identities.get(index);
            artifacts.add(new ExportArtifactSpec(schema.artifactName(), schema.artifactName() + ".csv",
                    schema.columns().stream().map(DataframeColumn::name).toList(), identity.epoch(), identity.identityHash(), "a".repeat(64), "b".repeat(64)));
        }
        var plan = new ExportPlan(1,
                new ExportProfile("fault-profile", ExportMode.COMPLETE, List.of("masks", "hashes")),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"), artifacts);
        var rows = new ArrayList<ArtifactRow>();
        backend.snapshots().stream(new SnapshotRequest(plan), new SnapshotRowConsumer() {
            public void begin(SnapshotMetadata metadata) { }
            public void beginArtifact(SnapshotArtifactMetadata metadata) { }
            public void row(ArtifactRow row) { rows.add(row); }
            public void endArtifact() { }
            public void end() { }
        });
        return rows;
    }

    long number(String sql) throws SQLException {
        try (var connection = dataframe.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue(); return rows.getLong(1);
        }
    }

    String text(String sql) throws SQLException {
        try (var connection = dataframe.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue(); return rows.getString(1);
        }
    }

    List<List<Object>> facts(String table) throws SQLException {
        try (var connection = dataframe.getConnection()) { return facts(connection, table); }
    }

    static List<List<Object>> facts(java.sql.Connection connection, String table) throws SQLException {
        var facts = new ArrayList<List<Object>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
            while (rows.next()) {
                var values = new ArrayList<Object>();
                for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++) { values.add(rows.getObject(index)); }
                facts.add(values);
            }
        }
        facts.sort(Comparator.comparing(Object::toString)); return facts;
    }

    private static HikariDataSource database(Path path) {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("fault-test", "jdbc:sqlite:" + path, "low-memory", 4, 4));
    }

    private static DataframeArtifactSchema schema(String artifact, String key) {
        return new DataframeArtifactSchema(artifact, List.of(new DataframeColumn("id", "INTEGER"),
                new DataframeColumn(key), new DataframeColumn("source"), new DataframeColumn("description")));
    }

    private static ArtifactIdentityDefinition identity(String artifact, String key) {
        return new ArtifactIdentityDefinition(artifact, new CanonicalKeyDefinition(artifact + "-row-v1", CanonicalKeyMode.COMPOSITE, List.of(key)),
                List.of(new CanonicalKeyDefinition(artifact + "-match-v1", CanonicalKeyMode.COMPOSITE, List.of(key))), 1);
    }

    @Override public void close() { try { service.close(); } finally { dataframe.close(); } }
}
