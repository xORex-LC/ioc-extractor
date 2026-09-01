package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.DataframeImportPromotionService;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportPromotionPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRequestedSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import com.iocextractor.application.tck.dataframeimport.CanonicalImportWriterContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the canonical import TCK and atomicity matrix against real SQLite files. */
@IntegrationTest
@ContractTest
class JdbcCanonicalImportWriterContractIT extends CanonicalImportWriterContractTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofHours(12);
    private static final String DIGEST = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);

    @TempDir
    Path tempDir;

    private final AtomicInteger databases = new AtomicInteger();
    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void closeDataSources() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Override
    protected Fixture createFixture() {
        Environment environment = environment("tck");
        CanonicalImportCommand command = environment.stage(
                "delivery-tck", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example", "source", "feed-a"), OptionalLong.empty()))),
                List.of());
        JdbcCanonicalImportWriter writer = environment.writer(JdbcCanonicalImportObserver.NOOP);
        return new Fixture(writer, command, () -> {
            assertThat(environment.count("masks")).isOne();
            assertThat(environment.count("masks_sources")).isOne();
            assertThat(environment.count("canonical_match_alias")).isOne();
            assertThat(environment.count("import_commit")).isOne();
            assertThat(environment.queryLong(
                    "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                    .isOne();
        });
    }

    @Test
    void everyPreCommitFailureRollsBackAllFanOutArtifactsAndReceiptEvidence() {
        EnumSet<JdbcCanonicalImportObserver.Phase> phases = EnumSet.allOf(
                JdbcCanonicalImportObserver.Phase.class);
        phases.remove(JdbcCanonicalImportObserver.Phase.AFTER_COMMIT);
        for (JdbcCanonicalImportObserver.Phase phase : phases) {
            Environment environment = environment("rollback-" + phase.name());
            CanonicalImportCommand command = environment.stage(
                    "delivery-" + phase.name(), ImportPromotionPolicy.defaults(),
                    List.of(row(2,
                            branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                    values("mask", "evil.example"), OptionalLong.empty()),
                            branch(environment, "hashes", ImportArtifactRole.RELATED,
                                    values("hash", "ABCDEF"), OptionalLong.empty()))),
                    List.of());
            JdbcCanonicalImportWriter writer = environment.writer(observed -> {
                if (observed == phase) {
                    throw new InjectedFailure(phase);
                }
            });

            assertThatThrownBy(() -> writer.promote(command))
                    .hasRootCauseInstanceOf(InjectedFailure.class);
            assertThat(environment.count("masks")).as(phase.name()).isZero();
            assertThat(environment.count("hashes")).as(phase.name()).isZero();
            assertThat(environment.count("canonical_match_alias")).as(phase.name()).isZero();
            assertThat(environment.count("import_commit")).as(phase.name()).isZero();
            assertThat(environment.count("import_commit_artifact")).as(phase.name()).isZero();
        }
    }

    @Test
    void crashAfterCommitResumesFromReceiptWithoutReapplyingCanonicalEffects() {
        Environment environment = environment("after-commit");
        CanonicalImportCommand command = environment.stage(
                "delivery-after-commit", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example"), OptionalLong.empty()))), List.of());
        JdbcCanonicalImportWriter crashing = environment.writer(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.AFTER_COMMIT) {
                throw new InjectedFailure(phase);
            }
        });

        assertThatThrownBy(() -> crashing.promote(command)).hasRootCauseInstanceOf(InjectedFailure.class);
        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_commit")).isOne();
        environment.deleteStage(command);

        var replay = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(replay.outcome().name()).isEqualTo("ALREADY_COMMITTED");
        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("masks_sources")).isOne();
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isOne();
    }

    @Test
    void runtimeWriterSamplesLifecycleTimeInsideItsOwnedTransaction() {
        Environment environment = environment("runtime-clock");
        CanonicalImportCommand command = environment.stage(
                "delivery-runtime-clock", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "clock.example"), OptionalLong.empty()))), List.of());

        environment.runtimeWriter().promote(command);

        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_commit")).isOne();
    }

    @Test
    void serviceLedgerRemainsPromotingAcrossPostCommitCrashThenAdvancesFromReceipt() {
        Environment environment = environment("saga-resume");
        CanonicalImportCommand command = environment.stage(
                "delivery-saga", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "saga.example"), OptionalLong.empty()))), List.of());
        JdbcImportDeliveryLedger ledger = stagedLedger(command);
        JdbcCanonicalImportWriter crashing = environment.writer(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.AFTER_COMMIT) {
                throw new InjectedFailure(phase);
            }
        });

        assertThatThrownBy(() -> new DataframeImportPromotionService(
                ledger, crashing, CLOCK).processNext())
                .hasRootCauseInstanceOf(InjectedFailure.class);
        assertThat(ledger.find(command.deliveryId()).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.PROMOTING);
        assertThat(environment.count("masks")).isOne();

        var resumed = new DataframeImportPromotionService(
                ledger, environment.writer(JdbcCanonicalImportObserver.NOOP), CLOCK).processNext();

        assertThat(resumed.workPerformed()).isTrue();
        assertThat(ledger.find(command.deliveryId()).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_commit")).isOne();
    }

    @Test
    void mergeConflictRejectsEveryFanOutBranchBeforeCanonicalMutation() {
        Environment environment = environment("fanout-reject");
        CanonicalImportCommand seed = environment.stage(
                "delivery-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "hashes", ImportArtifactRole.PRIMARY,
                        values("hash", "ABCDEF", "description", "known"), OptionalLong.empty()))),
                List.of());
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(seed);

        Map<String, ImportMergePolicy> rejecting = Map.of(
                "hash", ImportMergePolicy.AUTHORITATIVE,
                "description", ImportMergePolicy.REJECT_CONFLICT);
        CanonicalImportCommand command = environment.stage(
                "delivery-conflict", ImportPromotionPolicy.defaults(),
                List.of(row(3,
                        branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "new.example"), OptionalLong.empty()),
                        branch(environment, "hashes", ImportArtifactRole.RELATED,
                                values("hash", "ABCDEF", "description", "different"),
                                rejecting, OptionalLong.empty()))), List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(result.acceptedRows()).isZero();
        assertThat(result.rejectedRows()).isOne();
        assertThat(result.publicMutations()).isZero();
        assertThat(environment.count("masks")).isZero();
        assertThat(environment.count("hashes")).isOne();
        assertThat(environment.queryString("SELECT description FROM hashes"))
                .isEqualTo("known");
        assertThat(environment.queryString("""
                SELECT diagnostic_code FROM import_row_rejection
                WHERE delivery_id = 'delivery-conflict'
                """)).isEqualTo("IMPORT.MERGE_CONFLICT");
    }

    @Test
    void rejectDeliveryPolicyCommitsOnlyReceiptWhenAnyStagedRowIsInvalid() {
        Environment environment = environment("reject-delivery");
        ImportPromotionPolicy policy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.REJECT_DELIVERY, false, Optional.empty());
        CanonicalImportCommand command = environment.stage(
                "delivery-rejected", policy,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "would-not-commit.example"), OptionalLong.empty()))),
                List.of(new ImportRejectedLogicalRow(3, List.of(
                        new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE")))));

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(result.acceptedRows()).isZero();
        assertThat(result.rejectedRows()).isEqualTo(2);
        assertThat(environment.count("masks")).isZero();
        assertThat(environment.count("import_commit")).isOne();
        assertThat(environment.count("import_row_rejection")).isEqualTo(2);
    }

    @Test
    void authoritativeClearMutatesPublicBytesOnceWhileNoOpRenewPolicyControlsTtlOnly() {
        Environment environment = environment("ttl-merge");
        CanonicalImportCommand seed = environment.stage(
                "delivery-ttl-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example", "description", "old"), OptionalLong.empty()))),
                List.of());
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(seed);
        long firstDeadline = environment.queryLong("SELECT _valid_until_epoch_ms FROM masks");

        CanonicalImportCommand clear = environment.stage(
                "delivery-clear", new ImportPromotionPolicy(
                        ImportRowFailurePolicy.ACCEPT_VALID, true, Optional.empty()),
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        cells("mask", ImportCell.value("evil.example"),
                                "description", ImportCell.nullValue()), OptionalLong.empty()))),
                List.of());
        Instant clearTime = NOW.plus(Duration.ofHours(1));
        var cleared = environment.writer(JdbcCanonicalImportObserver.NOOP, clearTime).promote(clear);

        assertThat(cleared.publicMutations()).isOne();
        assertThat(environment.queryString("SELECT description FROM masks")).isNull();
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isEqualTo(2);
        long renewedDeadline = environment.queryLong("SELECT _valid_until_epoch_ms FROM masks");
        assertThat(renewedDeadline).isEqualTo(firstDeadline + Duration.ofHours(1).toMillis());

        CanonicalImportCommand noOp = environment.stage(
                "delivery-noop", ImportPromotionPolicy.defaults(),
                List.of(row(4, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example"), OptionalLong.empty()))), List.of());
        var unchanged = environment.writer(
                JdbcCanonicalImportObserver.NOOP, NOW.plus(Duration.ofHours(2))).promote(noOp);

        assertThat(unchanged.publicMutations()).isZero();
        assertThat(unchanged.affectedArtifacts()).isEmpty();
        assertThat(unchanged.observedArtifacts()).isEmpty();
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isEqualTo(2);
        assertThat(environment.queryLong("SELECT _valid_until_epoch_ms FROM masks"))
                .isEqualTo(renewedDeadline);
    }

    @Test
    void requestedSlotUsesExactPreferenceThenLowestAvailableFallbackAtomically() {
        Environment environment = environment("preferred-slot");
        ImportPromotionPolicy slotPolicy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        CanonicalImportCommand first = environment.stage(
                "delivery-slot-1", slotPolicy,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "one.example"), OptionalLong.of(7)))), List.of());
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(first);
        CanonicalImportCommand second = environment.stage(
                "delivery-slot-2", slotPolicy,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "two.example"), OptionalLong.of(7)))), List.of());

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(second);

        assertThat(environment.queryLong("""
                SELECT slot FROM export_slot_assignment
                WHERE profile = 'reputation' AND lifecycle_id = 1
                """)).isEqualTo(7);
        assertThat(environment.queryLong("""
                SELECT slot FROM export_slot_assignment
                WHERE profile = 'reputation' AND lifecycle_id = 2
                """)).isOne();
        assertThat(environment.count("export_slot_assignment")).isEqualTo(2);
        assertThat(environment.queryString("""
                SELECT outcome FROM import_slot_resolution
                WHERE delivery_id = 'delivery-slot-2'
                """)).isEqualTo("OCCUPIED_FALLBACK");
    }

    @Test
    void requestedSlotsAreScopedByProfileAndArtifact() {
        Environment environment = environment("preferred-slot-scope");
        ImportPromotionPolicy slotPolicy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        CanonicalImportCommand command = environment.stage(
                "delivery-slot-scope", slotPolicy,
                List.of(
                        row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "one.example"), OptionalLong.of(7))),
                        row(3, branch(environment, "hashes", ImportArtifactRole.PRIMARY,
                                values("hash", "AABB"), OptionalLong.of(7)))),
                List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(result.acceptedRows()).isEqualTo(2);
        assertThat(environment.queryLong("""
                SELECT COUNT(*) FROM export_slot_assignment
                WHERE profile = 'reputation' AND slot = 7
                """)).isEqualTo(2);
    }

    @Test
    void preserveExistingSlotKeepsSurvivorAssignmentAndPersistsSafeMismatchEvidence() {
        Environment environment = environment("survivor-slot-preserve");
        ImportPromotionPolicy preserve = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(environment.stage(
                "delivery-survivor-seed", preserve,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "survivor.example", "description", "old"),
                        OptionalLong.of(7)))), List.of()));
        CanonicalImportCommand update = environment.stage(
                "delivery-survivor-preserve", preserve,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "survivor.example", "description", "new"),
                        OptionalLong.of(8)))), List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(update);

        assertThat(result.acceptedRows()).isOne();
        assertThat(environment.queryString("SELECT description FROM masks")).isEqualTo("new");
        assertThat(environment.queryLong("SELECT slot FROM export_slot_assignment")).isEqualTo(7);
        assertThat(environment.queryString("""
                SELECT outcome FROM import_slot_resolution
                WHERE delivery_id = 'delivery-survivor-preserve'
                """)).isEqualTo("SURVIVOR_MISMATCH_PRESERVED");
    }

    @Test
    void rejectMismatchPolicyRejectsWholeLogicalRowBeforeBusinessMutation() {
        Environment environment = environment("survivor-slot-reject");
        ImportPromotionPolicy preserve = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(environment.stage(
                "delivery-strict-seed", preserve,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "strict.example", "description", "old"),
                        OptionalLong.of(7)))), List.of()));
        ImportPromotionPolicy strict = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.REJECT_MISMATCH)));
        CanonicalImportCommand update = environment.stage(
                "delivery-survivor-reject", strict,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "strict.example", "description", "new"),
                        OptionalLong.of(8)))), List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(update);

        assertThat(result.acceptedRows()).isZero();
        assertThat(result.rejectedRows()).isOne();
        assertThat(environment.queryString("SELECT description FROM masks")).isEqualTo("old");
        assertThat(environment.queryLong("SELECT slot FROM export_slot_assignment")).isEqualTo(7);
        assertThat(environment.queryString("""
                SELECT diagnostic_code FROM import_row_rejection
                WHERE delivery_id = 'delivery-survivor-reject'
                """)).isEqualTo("IMPORT.EXISTING_SLOT_MISMATCH");
    }

    private Environment environment(String name) {
        Path database = tempDir.resolve(databases.incrementAndGet() + "-" + name + ".db");
        HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + database, "low-memory", 4, 4));
        dataSources.add(dataSource);
        List<DataframeArtifactSchema> schemas = List.of(
                schema("masks", "mask"), schema("hashes", "hash"));
        List<ArtifactIdentityDefinition> identities = List.of(
                identity("masks", "mask"), identity("hashes", "hash"));
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        new JdbcArtifactIdentityStore(dataSource, CLOCK).ensureAll(identities);
        activate(dataSource, schemas);
        return new Environment(dataSource, tempDir.resolve("stages-" + name), schemas, identities);
    }

    private JdbcImportDeliveryLedger stagedLedger(CanonicalImportCommand command) {
        Path database = tempDir.resolve(databases.incrementAndGet() + "-service.db");
        HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", "jdbc:sqlite:" + database, "low-memory", 4, 4));
        dataSources.add(dataSource);
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        JdbcImportDeliveryLedger ledger = new JdbcImportDeliveryLedger(dataSource);
        ledger.reserveClaim(new ImportClaimReservation(
                command.deliveryId(), command.sourceId(), "candidate-saga", NOW));
        transition(ledger, command, ImportDeliveryState.DETECTED,
                ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        transition(ledger, command, ImportDeliveryState.CLAIMING,
                ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());
        transition(ledger, command, ImportDeliveryState.CLAIMED,
                ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryCheckpoint.snapshot(command.snapshot()));
        transition(ledger, command, ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryCheckpoint.contract(command.contract()));
        transition(ledger, command, ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none());
        transition(ledger, command, ImportDeliveryState.STAGING,
                ImportDeliveryState.STAGED,
                ImportDeliveryCheckpoint.stage(command.stage()));
        return ledger;
    }

    private void transition(JdbcImportDeliveryLedger ledger,
                            CanonicalImportCommand command,
                            ImportDeliveryState expected,
                            ImportDeliveryState next,
                            ImportDeliveryCheckpoint checkpoint) {
        var delivery = ledger.find(command.deliveryId()).orElseThrow();
        assertThat(delivery.state()).isEqualTo(expected);
        assertThat(ledger.transition(new ImportDeliveryTransition(
                command.deliveryId(), expected, delivery.version(), next,
                Optional.empty(), checkpoint, Optional.empty(), NOW)).name())
                .isEqualTo("APPLIED");
    }

    private DataframeArtifactSchema schema(String artifact, String keyColumn) {
        return new DataframeArtifactSchema(artifact, List.of(
                new DataframeColumn("id", "INTEGER"),
                new DataframeColumn(keyColumn, "TEXT"),
                new DataframeColumn("source", "TEXT"),
                new DataframeColumn("description", "TEXT")));
    }

    private ArtifactIdentityDefinition identity(String artifact, String keyColumn) {
        return new ArtifactIdentityDefinition(
                artifact,
                new CanonicalKeyDefinition(
                        artifact + "-row-v1", CanonicalKeyMode.COMPOSITE, List.of(keyColumn)),
                List.of(new CanonicalKeyDefinition(
                        artifact + "-match-v1", CanonicalKeyMode.COMPOSITE, List.of(keyColumn))),
                1);
    }

    private void activate(HikariDataSource dataSource, List<DataframeArtifactSchema> schemas) {
        var control = new JdbcLifecycleControlStore(dataSource, schemas);
        var disabled = control.load();
        var activating = disabled.beginActivation("import-test-fixed-12h-v1");
        assertThat(control.compareAndSet(disabled, activating)).isTrue();
        assertThat(control.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();
    }

    private ImportLogicalRow row(long sourceRow, ImportArtifactBranch... branches) {
        return new ImportLogicalRow(sourceRow, List.of(branches));
    }

    private ImportArtifactBranch branch(Environment environment,
                                        String artifact,
                                        ImportArtifactRole role,
                                        Map<String, ImportCell> cells,
                                        OptionalLong requestedSlot) {
        Map<String, ImportMergePolicy> policies = new LinkedHashMap<>();
        cells.keySet().forEach(column -> policies.put(column, ImportMergePolicy.AUTHORITATIVE));
        return branch(environment, artifact, role, cells, policies, requestedSlot);
    }

    private ImportArtifactBranch branch(Environment environment,
                                        String artifact,
                                        ImportArtifactRole role,
                                        Map<String, ImportCell> cells,
                                        Map<String, ImportMergePolicy> policies,
                                        OptionalLong requestedSlot) {
        Map<String, String> keyValues = new LinkedHashMap<>();
        cells.forEach((column, cell) -> keyValues.put(column,
                cell.presence() == ImportCell.Presence.VALUE ? cell.value() : null));
        CanonicalArtifactKeyResolver resolver = new CanonicalArtifactKeyResolver(environment.identities());
        ArtifactRow keyRow = ArtifactRow.ordered(keyValues);
        CanonicalKeyMaterial recordKey = resolver.recordKeyOf(artifact, keyRow).orElseThrow();
        return new ImportArtifactBranch(
                artifact, role, cells, policies, requestedSlot, Optional.of(recordKey),
                resolver.matchKeysOf(artifact, keyRow));
    }

    private Map<String, ImportCell> values(String... pairs) {
        Map<String, ImportCell> cells = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            cells.put(pairs[index], ImportCell.value(pairs[index + 1]));
        }
        return cells;
    }

    private Map<String, ImportCell> cells(Object... pairs) {
        Map<String, ImportCell> cells = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            cells.put((String) pairs[index], (ImportCell) pairs[index + 1]);
        }
        return cells;
    }

    private final class Environment {
        private final HikariDataSource dataSource;
        private final Path workspaceRoot;
        private final List<DataframeArtifactSchema> schemas;
        private final List<ArtifactIdentityDefinition> identities;
        private long sequence;

        private Environment(HikariDataSource dataSource,
                            Path workspaceRoot,
                            List<DataframeArtifactSchema> schemas,
                            List<ArtifactIdentityDefinition> identities) {
            this.dataSource = dataSource;
            this.workspaceRoot = workspaceRoot;
            this.schemas = schemas;
            this.identities = identities;
        }

        private List<ArtifactIdentityDefinition> identities() {
            return identities;
        }

        private JdbcCanonicalImportWriter writer(JdbcCanonicalImportObserver observer) {
            return writer(observer, NOW);
        }

        private JdbcCanonicalImportWriter writer(JdbcCanonicalImportObserver observer,
                                                  Instant effectiveAt) {
            return new JdbcCanonicalImportWriter(
                    dataSource, schemas,
                    List.of(
                            new ArtifactIdAllocatorDefinition(
                                    "masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition(
                                    "hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot,
                    ignored -> EffectiveTime.at(effectiveAt),
                    new FixedRecordValidityPolicy(TTL), CLOCK,
                    new JdbcWriterAdmission(), observer, Duration.ofDays(90));
        }

        private JdbcCanonicalImportWriter runtimeWriter() {
            return new JdbcCanonicalImportWriter(
                    dataSource, schemas,
                    List.of(
                            new ArtifactIdAllocatorDefinition(
                                    "masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition(
                                    "hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot,
                    new JdbcLifecycleClock(dataSource, CLOCK,
                            new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30))),
                    new FixedRecordValidityPolicy(TTL), CLOCK, new JdbcWriterAdmission());
        }

        private void deleteStage(CanonicalImportCommand command) {
            try {
                Files.delete(new ImportWorkspaceLayout(workspaceRoot)
                        .paths(command.deliveryId()).sealed());
            } catch (IOException failure) {
                throw new AssertionError("Cannot remove sealed stage for receipt replay test", failure);
            }
        }

        private CanonicalImportCommand stage(String delivery,
                                             ImportPromotionPolicy policy,
                                             List<ImportLogicalRow> rows,
                                             List<ImportRejectedLogicalRow> rejectedRows) {
            ImportDeliveryId deliveryId = new ImportDeliveryId(delivery);
            ImportSnapshot snapshot = new ImportSnapshot(
                    new ImportSnapshotReference("snapshot:" + delivery),
                    new ImportSha256(DIGEST), 100);
            ImportContractPin contract = new ImportContractPin(
                    new ImportContractId("contract-v1"), 1,
                    new ImportContractFingerprint(FINGERPRINT));
            CreateImportWorkspaceCommand create = new CreateImportWorkspaceCommand(
                    deliveryId, snapshot, contract, ImportDuplicatePolicy.COALESCE, policy);
            JdbcImportWorkspace workspace = new JdbcImportWorkspace(
                    workspaceRoot, workspaceLimits(), CLOCK);
            ImportStage stage;
            try (ImportWorkspaceWriter writer = workspace.create(create)) {
                rows.forEach(writer::append);
                rejectedRows.forEach(writer::reject);
                stage = writer.seal();
            }
            workspace.verifySealed(create, stage);
            return new CanonicalImportCommand(
                    deliveryId, new ImportDeliverySequence(++sequence),
                    new ImportSourceId("local-feed"), snapshot, contract, stage);
        }

        private long count(String table) {
            return queryLong("SELECT COUNT(*) FROM " + table);
        }

        private long queryLong(String sql) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        private String queryString(String sql) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private ImportWorkspaceLimits workspaceLimits() {
        return new ImportWorkspaceLimits(
                10_000, 8, 64, 10_000, 64L * 1024 * 1024,
                256L * 1024 * 1024, 192L * 1024 * 1024, 128L * 1024 * 1024,
                100,
                new com.iocextractor.application.dataframeimport.model.DelimitedInputLimits(
                        10_000, 64, 64 * 1024, 8 * 1024 * 1024));
    }

    private static final class InjectedFailure extends RuntimeException {
        private InjectedFailure(JdbcCanonicalImportObserver.Phase phase) {
            super(phase.name());
        }
    }
}
