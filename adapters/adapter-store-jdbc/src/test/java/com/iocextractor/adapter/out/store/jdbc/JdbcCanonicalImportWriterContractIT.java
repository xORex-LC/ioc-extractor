package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalMatchCardinality;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
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
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRequestedSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportRowWarning;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.tck.dataframeimport.CanonicalImportWriterContractTest;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    void acceptedWarningSurvivesCanonicalCommitAndReceiptReplay() {
        Environment environment = environment("accepted-warning");
        ImportRowWarning warning = new ImportRowWarning(2, "masks", "IMPORT.VIEW_FALLBACK");
        CanonicalImportCommand command = environment.stage(
                "delivery-accepted-warning", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example"), OptionalLong.empty()))),
                List.of(), List.of(warning));

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        var evidence = new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId());

        assertThat(evidence).hasValueSatisfying(receipt -> {
            assertThat(receipt.acceptedRows()).isOne();
            assertThat(receipt.rejectedRows()).isZero();
            assertThat(receipt.issues()).isEmpty();
            assertThat(receipt.warnings()).containsExactly(warning);
        });
        assertThat(environment.count("import_row_rejection")).isZero();
        assertThat(environment.count("import_row_warning")).isOne();
    }

    @Test
    void coalescedMemberWarningSurvivesCanonicalCommitAndReceiptReplay() {
        Environment environment = environment("coalesced-warning");
        ImportRowWarning warning = new ImportRowWarning(3, "masks", "IMPORT.VIEW_FALLBACK");
        CanonicalImportCommand command = environment.stage(
                "delivery-coalesced-warning", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                cells("mask", ImportCell.value("evil.example"),
                                        "source", ImportCell.absent()), OptionalLong.empty())),
                        row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "evil.example", "source", "second-feed"),
                                OptionalLong.empty()))),
                List.of(), List.of(warning));

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        environment.deleteStage(command);
        var evidence = new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId());

        assertThat(evidence).hasValueSatisfying(receipt -> {
            assertThat(receipt.acceptedRows()).isOne();
            assertThat(receipt.warnings()).containsExactly(warning);
        });
        assertThat(environment.queryLong("SELECT COUNT(*) FROM masks WHERE source = 'second-feed'"))
                .isOne();
        assertThat(environment.count("import_row_warning")).isOne();
    }

    @Test
    void promotesPinnedVersionTwoStageWithoutAWarningTable() throws Exception {
        Environment environment = environment("version-two-stage");
        CanonicalImportCommand command = environment.stage(
                "delivery-version-two", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "old.example"), OptionalLong.empty()))), List.of());
        Path sealed = environment.stagePath(command);
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sealed);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE stage_row_warning");
            statement.execute("PRAGMA user_version=2");
            statement.execute("""
                    UPDATE stage_meta SET schema_version=2,
                    plan_hash='9ab1998b7e0d179935dfedf374f6011d609d6db6bafed535deee771626524d64'
                    """);
        }
        ImportStage oldStage = new ImportStage(command.stage().reference(),
                ImportFileDigests.sha256(sealed), command.stage().sourceRows(),
                command.stage().acceptedRows(), command.stage().rejectedRows());
        CanonicalImportCommand resumed = new CanonicalImportCommand(
                command.deliveryId(), command.sequence(), command.sourceId(),
                command.snapshot(), command.contract(), oldStage);

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(resumed);

        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_row_warning")).isZero();
    }

    @Test
    @Timeout(20)
    void pendingFanOutIsInvisibleAndReceiptPublicationPreservesAnExistingReadSnapshot() throws Exception {
        Environment environment = environment("pending-visibility");
        var mask = branch(environment, "masks", ImportArtifactRole.PRIMARY,
                values("mask", "evil.example"), OptionalLong.of(7));
        var matches = new JdbcCanonicalMatchPlanner(environment.dataSource, environment.schemas);
        var requests = List.of(new CanonicalMatchRequest("mask", mask.matchKeys()));
        ImportPromotionPolicy slotPolicy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        ImportRowWarning warning = new ImportRowWarning(2, "masks", "IMPORT.VIEW_FALLBACK");
        CanonicalImportCommand command = environment.stage(
                "delivery-pending-visibility", slotPolicy,
                List.of(row(2, mask,
                        branch(environment, "hashes", ImportArtifactRole.RELATED,
                                values("hash", "ABCDEF"), OptionalLong.empty()))),
                List.of(new ImportRejectedLogicalRow(3, List.of(
                        new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE")))), List.of(warning));
        var publicationReady = new java.util.concurrent.CountDownLatch(1);
        var releaseCommit = new java.util.concurrent.CountDownLatch(1);
        JdbcCanonicalImportWriter writer = environment.writer(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.BEFORE_COMMIT) {
                publicationReady.countDown();
                try {
                    if (!releaseCommit.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Canonical publication was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Canonical publication interrupted", interrupted);
                }
            }
        });
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection reader = environment.dataSource.getConnection()) {
            reader.setAutoCommit(false);
            var promotion = workers.submit(() -> writer.promote(command));
            assertThat(publicationReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            List<String> publishedTables = List.of("masks", "hashes", "masks_sources", "hashes_sources",
                    "canonical_match_alias", "import_commit", "import_commit_artifact",
                    "export_slot_assignment", "import_slot_resolution",
                    "import_row_warning", "import_row_rejection",
                    "artifact_revision", "artifact_projection_state");
            for (String table : publishedTables) {
                assertThat(visibleCount(reader, table)).as("pending %s", table).isZero();
            }
            assertThat(new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId()))
                    .isEmpty();
            assertThat(matches.plan("masks", EffectiveTime.at(NOW), requests).getFirst().cardinality())
                    .isEqualTo(CanonicalMatchCardinality.ZERO);

            releaseCommit.countDown();
            assertThat(promotion.get(5, java.util.concurrent.TimeUnit.SECONDS).outcome().name())
                    .isEqualTo("COMMITTED");
            for (String table : publishedTables) {
                assertThat(visibleCount(reader, table)).as("pinned snapshot %s", table).isZero();
            }
            assertThat(matches.plan("masks", EffectiveTime.at(NOW), requests).getFirst().cardinality())
                    .isEqualTo(CanonicalMatchCardinality.ONE);
            reader.rollback();
            reader.setAutoCommit(true);
            assertThat(visibleCount(reader, "masks")).isOne();
            assertThat(visibleCount(reader, "hashes")).isOne();
            assertThat(visibleCount(reader, "canonical_match_alias")).isEqualTo(2);
            assertThat(visibleCount(reader, "import_commit")).isOne();
            assertThat(visibleCount(reader, "import_commit_artifact")).isEqualTo(2);
            assertThat(visibleCount(reader, "export_slot_assignment")).isOne();
            assertThat(visibleCount(reader, "import_slot_resolution")).isOne();
            assertThat(environment.queryLong("SELECT revision FROM artifact_revision WHERE artifact='masks'"))
                    .isOne();
            assertThat(environment.queryLong("SELECT revision FROM artifact_revision WHERE artifact='hashes'"))
                    .isOne();
            environment.deleteStage(command);
            assertThat(environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command)
                    .outcome().name()).isEqualTo("ALREADY_COMMITTED");
            assertThat(visibleCount(reader, "masks_sources")).isOne();
            assertThat(visibleCount(reader, "hashes_sources")).isOne();
            assertThat(environment.queryLong("SELECT SUM(occurrences) FROM masks_sources")).isOne();
            assertThat(environment.queryLong("SELECT SUM(occurrences) FROM hashes_sources")).isOne();
            assertThat(new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId()))
                    .hasValueSatisfying(receipt -> {
                        assertThat(receipt.acceptedRows()).isOne();
                        assertThat(receipt.rejectedRows()).isOne();
                        assertThat(receipt.warnings()).containsExactly(warning);
                        assertThat(receipt.issues()).containsExactly(
                                new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE"));
                    });
        } finally {
            releaseCommit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static long visibleCount(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + JdbcSql.quote(table));
             var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    @Timeout(30)
    void privatePreparationBurnsReservationsOnlyInTheAuthoritativeDatabase() {
        Environment working = environment("private-reservations-working");
        Environment authority = environment("private-reservations-authority");
        var command = working.stage("private-reservations", ImportPromotionPolicy.defaults(),
                List.of(row(2,
                        branch(working, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "reserved.example"), OptionalLong.empty()),
                        branch(working, "hashes", ImportArtifactRole.RELATED,
                                values("hash", "HASH-RESERVED"), OptionalLong.empty()))), List.of());
        var admission = new JdbcWriterAdmission();
        var failing = working.reservedWriter(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.BEFORE_COMMIT) {
                throw new IocExtractorException("Discard private preparation");
            }
        }, authority, admission);

        assertThatThrownBy(() -> failing.promote(command))
                .isInstanceOf(IocExtractorException.class).hasMessage("Discard private preparation");
        assertThat(working.count("masks")).isZero();
        assertThat(authority.count("masks")).isZero();
        assertThat(authority.queryLong("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'"))
                .isEqualTo(2);
        assertThat(authority.queryLong("SELECT next_value FROM lifecycle_id_allocator")).isEqualTo(3);
        assertThat(working.count("artifact_id_allocator")).isZero();

        working.reservedWriter(JdbcCanonicalImportObserver.NOOP, authority, admission).promote(command);
        assertThat(working.queryLong("SELECT id FROM masks")).isEqualTo(2);
        assertThat(working.queryLong("SELECT id FROM hashes")).isEqualTo(2);
        assertThat(working.queryLong("SELECT MIN(_lifecycle_id) FROM masks")).isEqualTo(3);
        assertThat(working.queryLong("SELECT MIN(_lifecycle_id) FROM hashes")).isEqualTo(4);
        assertThat(authority.queryLong("SELECT next_value FROM lifecycle_id_allocator")).isEqualTo(5);
        assertThat(authority.count("import_commit")).isZero();
    }

    @Test
    @Timeout(30)
    void typedHiddenChunksPublishRealFanOutSlotsAndReceiptForStageFreeRecovery() throws Exception {
        Environment privateBase = environment("typed-import-private");
        Environment publicBase = environment("typed-import-public");
        var rows = new ArrayList<ImportLogicalRow>();
        for (int index = 0; index < 60; index++) {
            rows.add(row(index + 2,
                    branch(privateBase, "masks", ImportArtifactRole.PRIMARY,
                            cells("mask", ImportCell.value("host-" + index + ".example"),
                                    "source", ImportCell.absent()),
                            index == 0 ? OptionalLong.of(7) : OptionalLong.empty()),
                    branch(privateBase, "hashes", ImportArtifactRole.RELATED,
                            cells("hash", ImportCell.value("HASH-" + index),
                                    "source", ImportCell.absent()), OptionalLong.empty())));
        }
        rows.add(row(62,
                branch(privateBase, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "host-0.example", "source", "coalesced-feed"), OptionalLong.of(7)),
                branch(privateBase, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "HASH-0", "source", "coalesced-feed"), OptionalLong.empty())));
        var warning = new ImportRowWarning(62, "masks", "IMPORT.VIEW_FALLBACK");
        var issue = new ImportRowIssue(63, "masks", "IMPORT.INVALID_VALUE");
        var policy = new ImportPromotionPolicy(ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy("reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        var command = privateBase.stage("typed-fan-out", policy, rows,
                List.of(new ImportRejectedLogicalRow(63, List.of(issue))), List.of(warning));
        var nativeResult = privateBase.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        assertThat(nativeResult.acceptedRows()).isEqualTo(60);
        assertThat(nativeResult.rejectedRows()).isOne();
        var admission = new JdbcWriterAdmission();
        var experiment = new SqliteImportPublicationExperiment(publicBase.dataSource, admission,
                List.of("masks", "hashes"));
        var chunkCount = new AtomicInteger();
        var attempt = experiment.stage(privateBase.dataSource, command.deliveryId().value(),
                command.stage().digest().value(), ordinal -> {
                    chunkCount.incrementAndGet();
                    assertThat(publicBase.count("masks")).isZero();
                    assertThat(publicBase.count("hashes")).isZero();
                    assertThat(publicBase.count("canonical_match_alias")).isZero();
                    assertThat(publicBase.count("export_slot_assignment")).isZero();
                    assertThat(new JdbcImportCommitEvidenceStore(publicBase.dataSource).find(command.deliveryId()))
                            .isEmpty();
                });
        assertThat(chunkCount.get()).isGreaterThan(1);
        Instant publicationUtc = NOW.plusSeconds(3);
        Environment corrected = environment("typed-import-corrected-oracle");
        Environment correctedStage = new Environment(corrected.dataSource, privateBase.workspaceRoot,
                corrected.schemas, corrected.identities);
        correctedStage.writer(JdbcCanonicalImportObserver.NOOP, publicationUtc).promote(command);
        var clock = new JdbcLifecycleClock(publicBase.dataSource, Clock.fixed(publicationUtc, ZoneOffset.UTC),
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission);
        try (var pinned = publicBase.dataSource.getConnection()) {
            pinned.setAutoCommit(false);
            assertThat(visibleCount(pinned, "masks")).isZero();
            var publication = experiment.publish(attempt, clock, new FixedRecordValidityPolicy(TTL),
                    command.stage().digest().value());
            assertThat(publication.effectiveMillis()).isEqualTo(publicationUtc.toEpochMilli());
            assertThat(visibleCount(pinned, "masks")).isZero();
            assertThat(visibleCount(pinned, "import_commit")).isZero();
            pinned.rollback();
            assertThat(visibleCount(pinned, "masks")).isEqualTo(60);
            assertThat(visibleCount(pinned, "hashes")).isEqualTo(60);
            assertThat(visibleCount(pinned, "canonical_match_alias")).isEqualTo(120);
        }
        assertThat(publicBase.queryLong("SELECT COUNT(*) FROM export_slot_assignment WHERE profile='reputation'"))
                .isEqualTo(60);
        assertThat(publicBase.queryLong("SELECT assigned_slot FROM import_slot_resolution")).isOne();
        assertThat(publicBase.queryLong("SELECT requested_slot FROM import_slot_resolution")).isEqualTo(7);
        assertThat(publicBase.queryString("SELECT outcome FROM import_slot_resolution"))
                .isEqualTo("OCCUPIED_FALLBACK");
        assertThat(publicBase.queryLong("SELECT COUNT(DISTINCT slot) FROM export_slot_assignment"))
                .isEqualTo(60);
        assertThat(publicBase.queryLong("SELECT revision FROM artifact_revision WHERE artifact='masks'"))
                .isOne();
        assertThat(publicBase.queryLong("SELECT required_generation FROM artifact_projection_state WHERE artifact='hashes'"))
                .isOne();
        assertThat(publicBase.queryString("SELECT source FROM masks WHERE mask='host-0.example'"))
                .isEqualTo("coalesced-feed");
        assertThat(publicBase.queryLong("SELECT _valid_until_epoch_ms FROM masks WHERE mask='host-0.example'"))
                .isEqualTo(publicationUtc.plus(TTL).toEpochMilli());
        var request = new CanonicalMatchRequest("typed-match", new CanonicalArtifactKeyResolver(publicBase.identities)
                .matchKeysOf("masks", ArtifactRow.ordered(Map.of("mask", "host-0.example"))));
        assertThat(new JdbcCanonicalMatchPlanner(publicBase.dataSource, publicBase.schemas)
                .plan("masks", EffectiveTime.at(publicationUtc), List.of(request)).getFirst().cardinality())
                .isEqualTo(CanonicalMatchCardinality.ONE);
        var evidence = new JdbcImportCommitEvidenceStore(publicBase.dataSource).find(command.deliveryId()).orElseThrow();
        assertThat(evidence.acceptedRows()).isEqualTo(60);
        assertThat(evidence.rejectedRows()).isOne();
        assertThat(evidence.warnings()).containsExactly(warning);
        assertThat(evidence.issues()).containsExactly(issue);
        for (String table : experiment.tables()) {
            assertThat(typedRows(publicBase.dataSource, table)).as("corrected canonical oracle: %s", table)
                    .isEqualTo(typedRows(corrected.dataSource, table));
        }
        privateBase.deleteStage(command);
        // Use the real canonical port and service journal; no replacement recovery algorithm.
        var replay = publicBase.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        assertThat(replay.outcome()).isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        assertThat(replay.effectiveAt()).isEqualTo(publicationUtc);
        var ledger = stagedLedger(command);
        var service = new DataframeImportPromotionService(ledger,
                publicBase.writer(JdbcCanonicalImportObserver.NOOP), CLOCK);
        service.processNext();
        assertThat(ledger.find(command.deliveryId()).orElseThrow().state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
        assertThat(publicBase.queryLong("SELECT SUM(occurrences) FROM masks_sources")).isEqualTo(60);
        assertThat(new JdbcImportCommitEvidenceStore(publicBase.dataSource).find(command.deliveryId()))
                .contains(evidence);
    }

    private static List<List<Object>> typedRows(HikariDataSource database, String table) throws SQLException {
        try (var c = database.getConnection(); var s = c.createStatement()) {
            var columns = new ArrayList<String>();
            try (var meta = s.executeQuery("PRAGMA table_info(" + JdbcSql.quote(table) + ")")) {
                while (meta.next()) { columns.add(meta.getString("name")); }
            }
            String projection = JdbcSql.joinedQuoted(columns);
            var values = new ArrayList<List<Object>>();
            try (var rows = s.executeQuery("SELECT " + projection + " FROM " + JdbcSql.quote(table)
                    + " ORDER BY " + projection)) {
                while (rows.next()) {
                    var row = new ArrayList<Object>();
                    for (int index = 1; index <= columns.size(); index++) { row.add(rows.getObject(index)); }
                    values.add(java.util.Collections.unmodifiableList(row));
                }
            }
            return List.copyOf(values);
        }
    }

    @Test
    @Timeout(30)
    void populatedTypedPublicationPreservesClearsCoalescingSlotsAndExpiryHistory() throws Exception {
        Environment working = environment("populated-working");
        Environment canonical = environment("populated-canonical");
        Environment oracle = environment("populated-oracle");
        var policy = new ImportPromotionPolicy(ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy("reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        var initial = working.stage("populated-initial", policy, List.of(row(2,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "existing.example", "description", "old"), OptionalLong.of(1)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "EXISTING-HASH"), OptionalLong.empty()))), List.of());
        working.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var canonicalWriter = new Environment(canonical.dataSource, working.workspaceRoot,
                canonical.schemas, canonical.identities);
        var oracleWriter = new Environment(oracle.dataSource, working.workspaceRoot, oracle.schemas, oracle.identities);
        canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var admission = new JdbcWriterAdmission();
        var experiment = new SqliteImportPublicationExperiment(canonical.dataSource, admission, List.of("masks", "hashes"));
        for (String table : experiment.tables()) {
            assertThat(typedRows(canonical.dataSource, table)).as("legacy absolute values: %s", table)
                    .isEqualTo(typedRows(oracle.dataSource, table));
        }

        var clear = row(2,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        cells("mask", ImportCell.value("existing.example"), "description", ImportCell.nullValue()),
                        OptionalLong.of(9)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "EXISTING-HASH"), OptionalLong.empty()));
        var added = row(3,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "new.example", "description", "new"), OptionalLong.of(1)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "NEW-HASH"), OptionalLong.empty()));
        var duplicate = row(4,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "new.example", "description", "new"), OptionalLong.of(1)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "NEW-HASH"), OptionalLong.empty()));
        var warning = new ImportRowWarning(4, "masks", "IMPORT.VIEW_FALLBACK");
        var update = working.stage("populated-update", policy, List.of(clear, added, duplicate), List.of(), List.of(warning));
        var renewals = new java.util.LinkedHashSet<String>();
        var observer = new JdbcCanonicalImportObserver() {
            public void after(Phase phase) { }
            public void afterMutation(String artifact, long canonicalRowId, boolean renewsValidity) {
                if (renewsValidity) { renewals.add(artifact + ":" + canonicalRowId); }
            }
        };
        working.reservedWriter(observer, canonical, admission).promote(update);
        Instant utc = NOW.plusSeconds(3);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP, utc).promote(update);
        var attempt = experiment.stage(working.dataSource, update.deliveryId().value(), update.stage().digest().value(), renewals, n -> {
            assertThat(canonical.count("masks")).isOne();
            assertThat(canonical.queryString("SELECT description FROM masks")).isEqualTo("old");
            assertThat(new JdbcImportCommitEvidenceStore(canonical.dataSource).find(update.deliveryId())).isEmpty();
        });
        experiment.publish(attempt, new JdbcLifecycleClock(canonical.dataSource, Clock.fixed(utc, ZoneOffset.UTC),
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission),
                new FixedRecordValidityPolicy(TTL), update.stage().digest().value());
        for (String table : experiment.tables()) {
            assertThat(typedRows(canonical.dataSource, table)).as("populated delta: %s", table)
                    .isEqualTo(typedRows(oracle.dataSource, table));
        }
        assertThat(canonical.queryObject("SELECT description FROM masks WHERE mask='existing.example'")).isNull();
        assertThat(canonical.queryLong("SELECT id FROM masks WHERE mask='new.example'")).isEqualTo(2);
        assertThat(canonical.queryLong("SELECT slot FROM export_slot_assignment WHERE lifecycle_id=1")).isOne();
        assertThat(new JdbcImportCommitEvidenceStore(canonical.dataSource).find(update.deliveryId()).orElseThrow().warnings())
                .containsExactly(warning);
        working.deleteStage(update);
        assertThat(canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(update).outcome())
                .isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);

        copyVisibleFixture(canonical.dataSource, working.dataSource, experiment.tables());
        assertThat(experiment.nearest("masks", NOW.toEpochMilli())).hasValue(NOW.plus(TTL).plusSeconds(3).toEpochMilli());
        Instant expiredUtc = NOW.plus(TTL).plusSeconds(3);
        var expired = working.stage("populated-expired", policy, List.of(row(2,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "existing.example", "description", "returned"), OptionalLong.empty()),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "EXISTING-HASH"), OptionalLong.empty()))), List.of());
        working.reservedWriter(JdbcCanonicalImportObserver.NOOP, canonical, admission, expiredUtc).promote(expired);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP, expiredUtc).promote(expired);
        var expiredAttempt = experiment.stage(working.dataSource, expired.deliveryId().value(),
                expired.stage().digest().value(), n -> {
                    assertThat(canonical.queryLong("SELECT id FROM masks WHERE mask='existing.example'")).isOne();
                    assertThat(canonical.count("masks_history")).isZero();
                });
        experiment.publish(expiredAttempt,
                new JdbcLifecycleClock(canonical.dataSource, Clock.fixed(expiredUtc, ZoneOffset.UTC),
                        new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission),
                new FixedRecordValidityPolicy(TTL), expired.stage().digest().value());
        for (String table : experiment.tables()) {
            assertThat(typedRows(canonical.dataSource, table)).as("expired reappearance: %s", table)
                    .isEqualTo(typedRows(oracle.dataSource, table));
        }
        assertThat(canonical.queryLong("SELECT id FROM masks WHERE mask='existing.example'")).isEqualTo(4);
        assertThat(canonical.queryLong("SELECT former_row_id FROM masks_history")).isOne();
        assertThat(canonical.count("masks")).isEqualTo(2);
        assertThat(experiment.nearest("masks", expiredUtc.toEpochMilli()))
                .hasValue(expiredUtc.plus(TTL).toEpochMilli());
        working.deleteStage(expired);
        assertThat(canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(expired).outcome())
                .isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
    }

    /** Finite C2 oracle hydration only; C3 must use selective streamed hydration. */
    private static void copyVisibleFixture(HikariDataSource source, HikariDataSource target, List<String> tables)
            throws SQLException {
        try (var c = target.getConnection(); var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            c.setAutoCommit(false);
            Throwable failure = null;
            try {
                for (String table : tables.reversed()) { s.executeUpdate("DELETE FROM " + JdbcSql.quote(table)); }
                for (String table : tables) {
                    var rows = typedRows(source, table);
                    if (rows.isEmpty()) { continue; }
                    try (var insert = c.prepareStatement("INSERT INTO " + JdbcSql.quote(table) + " VALUES ("
                            + JdbcSql.placeholders(rows.getFirst().size()) + ")")) {
                        for (var row : rows) {
                            for (int i = 0; i < row.size(); i++) { insert.setObject(i + 1, row.get(i)); }
                            insert.executeUpdate();
                        }
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException | Error caught) {
                failure = caught;
                try { c.rollback(); } catch (SQLException rollback) { caught.addSuppressed(rollback); }
                throw caught;
            } finally {
                try {
                    c.setAutoCommit(true);
                    s.execute("PRAGMA foreign_keys=ON");
                } catch (SQLException restoration) {
                    if (failure == null) { throw restoration; }
                    failure.addSuppressed(restoration);
                }
            }
        }
    }

    @Test
    @Timeout(30)
    void ordinaryPerArtifactPublicationCompletesAndReplaysTheRealTypedConfirmationReceipt() throws Exception {
        Environment working = environment("ordinary-private");
        Environment canonical = environment("ordinary-public");
        Environment oracle = environment("ordinary-oracle");
        var admission = new JdbcWriterAdmission();
        var experiment = new SqliteImportPublicationExperiment(canonical.dataSource, admission, List.of("masks", "hashes"));
        var context = new ConfirmationReceiptContext(new ConfirmationReceiptId("ordinary-receipt"),
                "ordinary-policy", 2, Duration.ofDays(30));
        var commands = new ArrayList<CanonicalArtifactConfirmation>();
        for (int index = 0; index < working.schemas.size(); index++) {
            var schema = working.schemas.get(index);
            var values = new LinkedHashMap<String, String>();
            for (var column : schema.columns()) { values.put(column.name(), null); }
            values.put(schema.artifactName().equals("masks") ? "mask" : "hash", "ordinary-" + index);
            values.put("source", "ordinary-feed");
            var row = ArtifactRow.ordered(values);
            var key = new CanonicalArtifactKeyResolver(working.identities).recordKeyOf(schema.artifactName(), row).orElseThrow();
            var command = new CanonicalArtifactConfirmation(new ObservationId("ordinary-observation"), "ordinary-source",
                    context, schema.artifactName(), List.copyOf(values.keySet()), List.of(new CanonicalRecordConfirmation(
                    new ArtifactRowKey(key.keyHash()), new PreparedArtifactRow(row, Optional.of("id")))));
            commands.add(command);
            new JdbcCanonicalLifecycleWriter(working.dataSource, working.schemas,
                    List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    ignored -> EffectiveTime.at(NOW), new FixedRecordValidityPolicy(TTL), CLOCK,
                    JdbcLifecycleTransactionObserver.NOOP, working.identities, new JdbcWriterAdmission(),
                    canonical.dataSource, admission).confirm(command);
            Instant utc = NOW.plusSeconds(2L * (index + 1));
            ordinaryWriter(oracle, utc).confirm(command);
            var attempt = experiment.stage(working.dataSource, "ordinary-unit-" + index, "ordinary-pins", n -> {
                assertThat(new JdbcConfirmationReceiptStore(canonical.dataSource, canonical.schemas, Duration.ofDays(30))
                        .findComplete("ordinary-source", "ordinary-policy", EffectiveTime.at(utc))).isEmpty();
            });
            experiment.publish(attempt, new JdbcLifecycleClock(canonical.dataSource, Clock.fixed(utc, ZoneOffset.UTC),
                    new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission),
                    new FixedRecordValidityPolicy(TTL), "ordinary-pins");
            for (String table : experiment.tables()) {
                assertThat(typedRows(canonical.dataSource, table)).as("ordinary unit %s: %s", index, table)
                        .isEqualTo(typedRows(oracle.dataSource, table));
            }
            copyVisibleFixture(canonical.dataSource, working.dataSource, experiment.tables());
        }
        var receipt = new JdbcConfirmationReceiptStore(canonical.dataSource, canonical.schemas, Duration.ofDays(30))
                .findComplete("ordinary-source", "ordinary-policy", EffectiveTime.at(NOW.plusSeconds(4))).orElseThrow();
        assertThat(receipt.artifacts()).hasSize(2);
        var before = typedRows(canonical.dataSource, "masks_sources");
        ordinaryWriter(canonical, NOW.plusSeconds(9)).confirm(commands.getFirst());
        assertThat(typedRows(canonical.dataSource, "masks_sources")).isEqualTo(before);
    }

    private JdbcCanonicalLifecycleWriter ordinaryWriter(Environment environment, Instant utc) {
        return new JdbcCanonicalLifecycleWriter(environment.dataSource, environment.schemas,
                List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                        new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                () -> EffectiveTime.at(utc), new FixedRecordValidityPolicy(TTL), CLOCK, environment.identities);
    }

    @Test
    @Timeout(30)
    void typedDeadlineIndexesFindTheNextLegacyDeadlineAtReferenceSize() throws Exception {
        Environment canonical = environment("deadline-reference");
        var initial = canonical.stage("deadline-reference-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "seed.example"), OptionalLong.empty()))), List.of());
        canonical.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var experiment = new SqliteImportPublicationExperiment(canonical.dataSource, new JdbcWriterAdmission(),
                List.of("masks", "hashes"));
        long deadline = NOW.plus(TTL).toEpochMilli();
        try (var c = canonical.dataSource.getConnection(); var s = c.createStatement()) {
            var names = new ArrayList<String>();
            try (var metadata = s.executeQuery("PRAGMA table_info(masks_import_probe)")) {
                while (metadata.next()) { names.add(metadata.getString("name")); }
            }
            var expressions = new ArrayList<String>();
            for (String name : names) {
                expressions.add(switch (name) {
                    case "id", "_lifecycle_id" -> "n+100";
                    case "mask" -> "'host-'||n||'.example'";
                    case "_row_key" -> "printf('%064x',n)";
                    case "address" -> "'reference:'||n";
                    case "absolute_deadline", "_valid_until_epoch_ms" -> deadline + "+n";
                    default -> "v." + JdbcSql.quote(name);
                });
            }
            s.executeUpdate("WITH RECURSIVE numbers(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM numbers WHERE n<100000)"
                    + " INSERT INTO masks_import_probe(" + JdbcSql.joinedQuoted(names) + ") SELECT "
                    + String.join(",", expressions) + " FROM numbers CROSS JOIN masks_import_probe v WHERE v.id=1");
            s.execute("ANALYZE");
            var plan = new ArrayList<String>();
            try (var query = c.prepareStatement("EXPLAIN QUERY PLAN " + experiment.nearestSql("masks"))) {
                query.setLong(1, deadline);
                query.setLong(2, deadline);
                try (var rows = query.executeQuery()) { while (rows.next()) { plan.add(rows.getString("detail")); } }
            }
            assertThat(plan).anyMatch(detail -> detail.contains("masks_import_probe_absolute_deadline"));
            assertThat(plan).anyMatch(detail -> detail.contains("masks_import_probe_deadline_anchor"));
            assertThat(plan).anyMatch(detail -> detail.contains("masks_import_probe_address"));
            assertThat(plan).anyMatch(detail -> detail.contains("experiment_unit_deadline"));
            long before = System.nanoTime();
            assertThat(canonical.queryLong("SELECT MIN(_valid_until_epoch_ms) FROM masks WHERE _valid_until_epoch_ms>"
                    + deadline)).isEqualTo(deadline + 1);
            long baseline = System.nanoTime() - before;
            long after = System.nanoTime();
            assertThat(experiment.nearest("masks", deadline)).hasValue(deadline + 1);
            long indexed = System.nanoTime() - after;
            System.out.println("C2_TYPED_DEADLINE rows=100001 baseline_ns=" + baseline
                    + " indexed_ns=" + indexed + " plan=" + plan);
            assertThat(experiment.nearest("masks", deadline + 100000)).isEmpty();
        }
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
    void orderedImportFieldUsesSharedRegistrationRankAcrossReverseCompletion() {
        Environment environment = environment("ordered-import");
        JdbcObservationRegistrationStore registrations =
                new JdbcObservationRegistrationStore(environment.dataSource, CLOCK);
        CanonicalImportCommand older = environment.stage(
                "ordered-older", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "ordered.example", "source", "older"),
                        OptionalLong.empty()))), List.of());
        CanonicalImportCommand newer = environment.stage(
                "ordered-newer", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "ordered.example", "source", "newer"),
                        OptionalLong.empty()))), List.of());
        RegisteredObservation olderRegistration = registrations.registerNew(
                new ObservationId(older.deliveryId().value()), ObservationOrigin.MANAGED_IMPORT);
        RegisteredObservation newerRegistration = registrations.registerNew(
                new ObservationId(newer.deliveryId().value()), ObservationOrigin.MANAGED_IMPORT);
        JdbcCanonicalImportWriter writer = environment.orderedWriter();

        writer.promote(withRegistration(newer, newerRegistration));
        writer.promote(withRegistration(older, olderRegistration));

        assertThat(environment.queryString("SELECT source FROM masks"))
                .isEqualTo("newer");
        assertThat(environment.queryLong("""
                SELECT admission_order FROM canonical_lifecycle_field_origin
                WHERE artifact = 'masks' AND field_name = 'source'
                """)).isEqualTo(newerRegistration.admissionOrder().value());
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isOne();
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

    @Test
    void sealedStageIntegrityRejectsMissingOrChangedBytes() throws IOException {
        Environment missingEnvironment = environment("stage-missing");
        CanonicalImportCommand missing = missingEnvironment.stage(
                "delivery-stage-missing", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(missingEnvironment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "missing.example"), OptionalLong.empty()))), List.of());
        missingEnvironment.deleteStage(missing);

        assertThatThrownBy(() -> missingEnvironment.writer(JdbcCanonicalImportObserver.NOOP).promote(missing))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Pinned import stage is not a regular sealed file");

        Environment changedEnvironment = environment("stage-changed");
        CanonicalImportCommand changed = changedEnvironment.stage(
                "delivery-stage-changed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(changedEnvironment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "changed.example"), OptionalLong.empty()))), List.of());
        Files.writeString(changedEnvironment.stagePath(changed), "tampered", StandardOpenOption.APPEND);

        assertThatThrownBy(() -> changedEnvironment.writer(JdbcCanonicalImportObserver.NOOP).promote(changed))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Pinned import stage digest does not match sealed bytes");
    }

    @Test
    void pinnedStageMetadataRejectsEveryPromotionEvidenceMismatch() {
        Environment environment = environment("stage-evidence");
        CanonicalImportCommand command = environment.stage(
                "delivery-stage-evidence", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evidence.example"), OptionalLong.empty()))),
                List.of(new ImportRejectedLogicalRow(3, List.of(
                        new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE")))));
        ImportSnapshot snapshot = command.snapshot();
        ImportContractPin contract = command.contract();
        ImportStage stage = command.stage();
        List<CanonicalImportCommand> mismatches = List.of(
                commandWith(command, new ImportSnapshot(
                        snapshot.reference(), new ImportSha256("c".repeat(64)), snapshot.size()), contract, stage),
                commandWith(command, new ImportSnapshot(
                        snapshot.reference(), snapshot.digest(), snapshot.size() + 1), contract, stage),
                commandWith(command, snapshot, new ImportContractPin(
                        new ImportContractId("other-v1"), contract.version(), contract.fingerprint()), stage),
                commandWith(command, snapshot, new ImportContractPin(
                        contract.id(), contract.version() + 1, contract.fingerprint()), stage),
                commandWith(command, snapshot, new ImportContractPin(
                        contract.id(), contract.version(), new ImportContractFingerprint("c".repeat(64))), stage),
                commandWith(command, snapshot, contract, new ImportStage(
                        stage.reference(), stage.digest(), stage.sourceRows() + 1,
                        stage.acceptedRows(), stage.rejectedRows())),
                commandWith(command, snapshot, contract, new ImportStage(
                        stage.reference(), stage.digest(), stage.sourceRows(),
                        stage.acceptedRows() - 1, stage.rejectedRows())),
                commandWith(command, snapshot, contract, new ImportStage(
                        stage.reference(), stage.digest(), stage.sourceRows(),
                        stage.acceptedRows(), stage.rejectedRows() - 1)));

        for (CanonicalImportCommand mismatch : mismatches) {
            assertThatThrownBy(() -> environment.writer(JdbcCanonicalImportObserver.NOOP).promote(mismatch))
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessage("Import stage metadata does not match promotion evidence");
        }
        assertThat(environment.count("import_commit")).isZero();
        assertThat(environment.count("masks")).isZero();
    }

    @Test
    void canonicalReceiptRejectsEveryIdentityMismatch() {
        Environment environment = environment("receipt-identity");
        CanonicalImportCommand command = environment.stage(
                "delivery-receipt-identity", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "receipt.example"), OptionalLong.empty()))), List.of());
        JdbcCanonicalImportWriter writer = environment.writer(JdbcCanonicalImportObserver.NOOP);
        writer.promote(command);
        List<ReceiptMutation> mutations = List.of(
                new ReceiptMutation("sequence_no", command.sequence().value() + 1),
                new ReceiptMutation("observation_id", "import:other-delivery"),
                new ReceiptMutation("source_id", "other-source"),
                new ReceiptMutation("snapshot_sha256", "c".repeat(64)),
                new ReceiptMutation("snapshot_size", command.snapshot().size() + 1),
                new ReceiptMutation("contract_id", "other-v1"),
                new ReceiptMutation("contract_version", command.contract().version() + 1),
                new ReceiptMutation("contract_fingerprint", "c".repeat(64)),
                new ReceiptMutation("stage_sha256", "c".repeat(64)));

        for (ReceiptMutation mutation : mutations) {
            Object original = environment.queryObject(
                    "SELECT " + mutation.column() + " FROM import_commit");
            try {
                environment.updateReceipt(command.deliveryId(), mutation.column(), mutation.value());
                assertThatThrownBy(() -> writer.promote(command))
                        .as(mutation.column())
                        .isInstanceOf(IocExtractorException.class)
                        .hasMessage("Import delivery identity conflicts with canonical receipt");
            } finally {
                environment.updateReceipt(command.deliveryId(), mutation.column(), original);
            }
        }
        assertThat(writer.promote(command).outcome().name()).isEqualTo("ALREADY_COMMITTED");
        assertThat(environment.count("masks")).isOne();
    }

    @Test
    void unchangedRenewalPersistsAndReplaysDeadlineOnlyArtifactEvidence() {
        Environment environment = environment("deadline-only-evidence");
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(environment.stage(
                "delivery-deadline-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "renew.example"), OptionalLong.empty()))), List.of()));
        ImportPromotionPolicy renewUnchanged = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, true, Optional.empty());
        CanonicalImportCommand renewal = environment.stage(
                "delivery-deadline-only", renewUnchanged,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "renew.example"), OptionalLong.empty()))), List.of());
        JdbcCanonicalImportWriter writer = environment.writer(
                JdbcCanonicalImportObserver.NOOP, NOW.plus(Duration.ofHours(1)));

        var committed = writer.promote(renewal);
        var replayed = writer.promote(renewal);

        assertThat(committed.publicMutations()).isZero();
        assertThat(committed.affectedArtifacts()).isEmpty();
        assertThat(committed.observedArtifacts()).containsExactly("masks");
        assertThat(committed.projectionGenerations()).isEmpty();
        assertThat(replayed)
                .usingRecursiveComparison()
                .ignoringFields("outcome")
                .isEqualTo(committed);
        assertThat(replayed.outcome().name()).isEqualTo("ALREADY_COMMITTED");
        assertThat(environment.queryObject(
                """
                SELECT projection_generation FROM import_commit_artifact
                WHERE delivery_id = 'delivery-deadline-only'
                """)).isNull();
    }

    @Test
    void constructorRejectsAmbiguousDefinitionsAndNonPositiveReceiptRetention() {
        Environment environment = environment("writer-configuration");
        List<ArtifactIdAllocatorDefinition> allocators = List.of(
                new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1));

        assertThatThrownBy(() -> constructWriter(
                environment, List.of(schema("masks", "mask"), schema("masks", "mask")),
                allocators, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate dataframe artifact schema: masks");
        assertThatThrownBy(() -> constructWriter(
                environment, environment.schemas,
                List.of(allocators.getFirst(), allocators.getFirst()), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate public id allocator definition: masks");
        assertThatThrownBy(() -> constructWriter(
                environment, environment.schemas, allocators, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("receiptRetention must be positive");
        assertThatThrownBy(() -> constructWriter(
                environment, environment.schemas, allocators, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("receiptRetention must be positive");
    }

    private JdbcCanonicalImportWriter constructWriter(
            Environment environment,
            List<DataframeArtifactSchema> schemas,
            List<ArtifactIdAllocatorDefinition> allocators,
            Duration receiptRetention) {
        return new JdbcCanonicalImportWriter(
                environment.dataSource, schemas, allocators, environment.identities,
                environment.workspaceRoot, ignored -> EffectiveTime.at(NOW),
                new FixedRecordValidityPolicy(TTL), CLOCK, new JdbcWriterAdmission(),
                JdbcCanonicalImportObserver.NOOP, receiptRetention);
    }

    private CanonicalImportCommand commandWith(
            CanonicalImportCommand command,
            ImportSnapshot snapshot,
            ImportContractPin contract,
            ImportStage stage) {
        return new CanonicalImportCommand(
                command.deliveryId(), command.sequence(), command.sourceId(), snapshot, contract, stage);
    }

    private CanonicalImportCommand withRegistration(
            CanonicalImportCommand command, RegisteredObservation registration) {
        return new CanonicalImportCommand(
                command.deliveryId(), command.sequence(), command.sourceId(), command.snapshot(),
                command.contract(), command.stage(), registration);
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

        private JdbcCanonicalImportWriter reservedWriter(JdbcCanonicalImportObserver observer,
                                                          Environment authority,
                                                          JdbcWriterAdmission admission) {
            return reservedWriter(observer, authority, admission, NOW);
        }

        private JdbcCanonicalImportWriter reservedWriter(JdbcCanonicalImportObserver observer,
                                                          Environment authority,
                                                          JdbcWriterAdmission admission,
                                                          Instant utc) {
            return new JdbcCanonicalImportWriter(dataSource, schemas,
                    List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot, ignored -> EffectiveTime.at(utc),
                    new FixedRecordValidityPolicy(TTL), CLOCK, new JdbcWriterAdmission(),
                    observer, Duration.ofDays(90), Map.of(), authority.dataSource, admission);
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

        private JdbcCanonicalImportWriter orderedWriter() {
            ArtifactWritePolicy policy = new ArtifactWritePolicy(
                    ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST,
                    null,
                    Map.of("source",
                            ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
            return new JdbcCanonicalImportWriter(
                    dataSource, schemas,
                    List.of(
                            new ArtifactIdAllocatorDefinition(
                                    "masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition(
                                    "hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot,
                    ignored -> EffectiveTime.at(NOW),
                    new FixedRecordValidityPolicy(TTL), CLOCK,
                    new JdbcWriterAdmission(), JdbcCanonicalImportObserver.NOOP,
                    Duration.ofDays(90), Map.of("masks", policy));
        }

        private void deleteStage(CanonicalImportCommand command) {
            try {
                Files.delete(stagePath(command));
            } catch (IOException failure) {
                throw new AssertionError("Cannot remove sealed stage for receipt replay test", failure);
            }
        }

        private Path stagePath(CanonicalImportCommand command) {
            return new ImportWorkspaceLayout(workspaceRoot).paths(command.deliveryId()).sealed();
        }

        private CanonicalImportCommand stage(String delivery,
                                             ImportPromotionPolicy policy,
                                             List<ImportLogicalRow> rows,
                                             List<ImportRejectedLogicalRow> rejectedRows) {
            return stage(delivery, policy, rows, rejectedRows, List.of());
        }

        private CanonicalImportCommand stage(String delivery,
                                             ImportPromotionPolicy policy,
                                             List<ImportLogicalRow> rows,
                                             List<ImportRejectedLogicalRow> rejectedRows,
                                             List<ImportRowWarning> warnings) {
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
                rows.forEach(row -> writer.append(row,
                        warnings.stream().filter(warning -> warning.sourceRowNumber() == row.sourceRowNumber())
                                .toList()));
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

        private Object queryObject(String sql) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1);
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        private void updateReceipt(ImportDeliveryId deliveryId, String column, Object value) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE import_commit SET " + column + " = ? WHERE delivery_id = ?")) {
                statement.setObject(1, value);
                statement.setString(2, deliveryId.value());
                assertThat(statement.executeUpdate()).isOne();
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

    private record ReceiptMutation(String column, Object value) {
    }
}
