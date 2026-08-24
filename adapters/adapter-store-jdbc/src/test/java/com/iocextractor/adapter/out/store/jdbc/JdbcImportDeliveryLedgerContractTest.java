package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.DataframeImportAdmissionService;
import com.iocextractor.application.dataframeimport.ImportDeliverySnapshotPinned;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportCommand;
import com.iocextractor.application.tck.dataframeimport.ImportDeliveryLedgerContractTest;
import com.iocextractor.platform.events.ControlEvent;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcImportDeliveryLedgerContractTest extends ImportDeliveryLedgerContractTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    protected ImportDeliveryLedger createLedger() {
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", "jdbc:sqlite:" + tempDir.resolve("import-ledger.db"),
                        "low-memory", 1, 4));
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        return new JdbcImportDeliveryLedger(dataSource);
    }

    @Test
    void reopeningAdapterRecoversEveryPersistedPrePromotionCheckpoint() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery current = ledger.reserveClaim(new ImportClaimReservation(
                new ImportDeliveryId("delivery-restart"), new ImportSourceId("source"),
                "candidate-restart", NOW));

        ImportSnapshot snapshot = new ImportSnapshot(
                new ImportSnapshotReference("snapshot:restart"), new ImportSha256("a".repeat(64)), 1024);
        ImportContractPin contract = new ImportContractPin(
                new ImportContractId("ip-list-v1"), 1,
                new ImportContractFingerprint("b".repeat(64)));
        ImportStage stage = new ImportStage(
                new ImportStageReference("stage:restart"), new ImportSha256("c".repeat(64)), 10, 9, 1);
        List<CheckpointTransition> transitions = List.of(
                new CheckpointTransition(ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none()),
                new CheckpointTransition(ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none()),
                new CheckpointTransition(
                        ImportDeliveryState.SNAPSHOT_PINNED, ImportDeliveryCheckpoint.snapshot(snapshot)),
                new CheckpointTransition(
                        ImportDeliveryState.CONTRACT_PINNED, ImportDeliveryCheckpoint.contract(contract)),
                new CheckpointTransition(ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none()),
                new CheckpointTransition(ImportDeliveryState.STAGED, ImportDeliveryCheckpoint.stage(stage)),
                new CheckpointTransition(ImportDeliveryState.PROMOTING, ImportDeliveryCheckpoint.none()));

        for (CheckpointTransition transition : transitions) {
            current = transition(ledger, current, transition.state(), transition.checkpoint());
            ledger = new JdbcImportDeliveryLedger(dataSource);
            assertThat(ledger.find(current.id())).contains(current);
            assertThat(ledger.findRecoverable(1)).containsExactly(current);
        }
    }

    @Test
    void serviceSchemaUsesHeadIndexAndWritesCompactOrderedTransitionAudit() throws Exception {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery detected = ledger.reserveClaim(new ImportClaimReservation(
                new ImportDeliveryId("delivery-plan"), new ImportSourceId("source"),
                "candidate-plan", NOW));
        transition(ledger, detected, ImportDeliveryState.CLAIMING);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryPlan(connection, """
                    SELECT sequence_no
                    FROM import_delivery
                    WHERE state <> 'TERMINAL'
                    ORDER BY sequence_no
                    LIMIT 1
                    """))
                    .anyMatch(line -> line.contains("ix_import_delivery_head"));
            try (var resultSet = connection.createStatement().executeQuery("""
                    SELECT ordinal, from_state, to_state, safe_code
                    FROM import_delivery_transition
                    WHERE delivery_id = 'delivery-plan'
                    ORDER BY ordinal
                    """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt("ordinal")).isEqualTo(1);
                assertThat(resultSet.getString("safe_code")).isEqualTo("IMPORT.CLAIM_RESERVED");
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt("ordinal")).isEqualTo(2);
                assertThat(resultSet.getString("from_state")).isEqualTo("DETECTED");
                assertThat(resultSet.getString("to_state")).isEqualTo("CLAIMING");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    @Test
    void admissionPinsSnapshotBeforePublishingAndPersistsRetryWithoutSleeping() {
        ImportDeliveryLedger ledger = createLedger();
        ImportSnapshot snapshot = new ImportSnapshot(
                new ImportSnapshotReference("snapshot:admitted"), new ImportSha256("d".repeat(64)), 17);
        List<ControlEvent> events = new ArrayList<>();
        ManagedImportSourceLifecycle lifecycle = new ManagedImportSourceLifecycle() {
            @Override
            public List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
                return List.of();
            }

            @Override
            public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
                return new ClaimImportSourceResult(snapshot);
            }

            @Override
            public void disposition(DispositionImportSourceCommand command) {
            }

            @Override
            public void purgeSnapshot(ImportDeliveryId deliveryId, ImportSourceId sourceId) {
            }
        };
        var service = new DataframeImportAdmissionService(
                ledger, lifecycle, events::add,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

        var admitted = service.admit(new AdmitDataframeImportCommand(new ImportClaimReservation(
                new ImportDeliveryId("delivery-admitted"), new ImportSourceId("source"),
                "candidate-admitted", NOW)));

        assertThat(admitted.newlyReserved()).isTrue();
        assertThat(admitted.delivery().state()).isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(admitted.delivery().snapshot()).contains(snapshot);
        assertThat(events).singleElement().isInstanceOf(ImportDeliverySnapshotPinned.class);

        var duplicate = service.admit(new AdmitDataframeImportCommand(new ImportClaimReservation(
                new ImportDeliveryId("delivery-duplicate"), new ImportSourceId("source"),
                "candidate-admitted", NOW.plusSeconds(1))));
        assertThat(duplicate.newlyReserved()).isFalse();
        assertThat(duplicate.delivery().id()).isEqualTo(admitted.delivery().id());
        assertThat(events).hasSize(1);
    }

    @Test
    void retentionSelectionUnionsMaximumAgeAndMaximumCountWithinOutcomeTarget() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery oldest = terminalRejected(ledger, "oldest", NOW.minus(Duration.ofDays(10)));
        ImportDelivery outsideCount = terminalRejected(ledger, "outside-count", NOW.minus(Duration.ofDays(4)));
        terminalRejected(ledger, "kept-a", NOW.minus(Duration.ofDays(3)));
        terminalRejected(ledger, "kept-b", NOW.minus(Duration.ofDays(2)));
        var target = new ImportTerminalRetentionTarget(
                "unsuccessful", Set.of(ImportTerminalOutcome.REJECTED),
                Duration.ofDays(5), 2, RetentionAction.DELETE, null);

        assertThat(ledger.findRetentionCandidates(target, NOW, 10))
                .extracting(ImportDelivery::id)
                .containsExactly(oldest.id(), outsideCount.id());
    }

    private ImportDelivery terminalRejected(
            ImportDeliveryLedger ledger, String token, Instant terminalAt) {
        ImportDelivery current = ledger.reserveClaim(new ImportClaimReservation(
                new ImportDeliveryId("delivery-" + token), new ImportSourceId("source"),
                "candidate-" + token, terminalAt.minusSeconds(1)));
        ImportDeliveryTransition transition = new ImportDeliveryTransition(
                current.id(), current.state(), current.version(), ImportDeliveryState.TERMINAL,
                Optional.of(ImportTerminalOutcome.REJECTED), ImportDeliveryCheckpoint.none(),
                Optional.empty(), terminalAt);
        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        return ledger.find(current.id()).orElseThrow();
    }

    private ImportDelivery transition(ImportDeliveryLedger ledger,
                                      ImportDelivery current,
                                      ImportDeliveryState next) {
        return transition(ledger, current, next, ImportDeliveryCheckpoint.none());
    }

    private ImportDelivery transition(ImportDeliveryLedger ledger,
                                      ImportDelivery current,
                                      ImportDeliveryState next,
                                      ImportDeliveryCheckpoint checkpoint) {
        ImportDeliveryTransition transition = new ImportDeliveryTransition(
                current.id(), current.state(), current.version(), next, Optional.empty(),
                checkpoint, Optional.empty(), current.updatedAt().plusSeconds(1));
        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        return ledger.find(current.id()).orElseThrow();
    }

    private record CheckpointTransition(ImportDeliveryState state, ImportDeliveryCheckpoint checkpoint) {
    }

    private List<String> queryPlan(Connection connection, String sql) throws Exception {
        List<String> plan = new ArrayList<>();
        try (var resultSet = connection.createStatement().executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (resultSet.next()) {
                plan.add(resultSet.getString("detail"));
            }
        }
        return plan;
    }
}
