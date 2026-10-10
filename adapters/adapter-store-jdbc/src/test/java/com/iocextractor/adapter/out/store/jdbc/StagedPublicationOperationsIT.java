package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.dataframeimport.DataframeImportPromotionService;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.diagnostics.DiagnosticException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drained file-backed migration/restore and bounded operational candidate ownership, never activation evidence. */
@IntegrationTest
@Timeout(45)
class StagedPublicationOperationsIT {
    @TempDir Path root;

    @Test
    void adoptsPopulatedNativeFamiliesAndRecoversExistingReceiptWithoutStage() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            var command = fixture.stageAndRegister();
            var writer = fixture.backend.nativeImport(fixture.dataframe, EffectiveTime.at(StagedPublicationFixture.NOW), fixture.backend.readContext());
            new DataframeImportPromotionService(fixture.ledger, writer, StagedPublicationFixture.CLOCK).processNext();
            finish(fixture);
            JdbcStagedCanonicalBackend.initializeIds(fixture.backend.configuration());
            var expected = facts(fixture);

            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                assertThat(operations.health().ready()).isFalse();
                assertThat(operations.recoverPage(256)).isTrue();
                assertThat(operations.health().ready()).isTrue();
                assertThat(fixture.number("PRAGMA user_version")).isEqualTo(13);
                assertThat(facts(fixture)).as("literal fields, deadlines, IDs, receipts, slots and provenance survive migration").isEqualTo(expected);
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_version_masks WHERE publication_columns<>'' OR deadline_anchor IS NOT NULL")).isZero();
                assertThatThrownBy(() -> new SqliteUserVersionSchemaMigrator(fixture.dataframe, DataframeFormatMigrations.sqlite()).migrate())
                        .isInstanceOf(DiagnosticException.class);
                deleteStage(root.resolve("stage"));
                var before = facts(fixture);
                assertThat(operations.use(b -> b.imports().promote(command)).publicMutations()).isEqualTo(160);
                assertThat(facts(fixture)).isEqualTo(before);
            }
            try (var reopened = JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration())) {
                assertThat(reopened.recoverPage(256)).isTrue();
                reopened.use(b -> b.confirmations().confirm(fixture.document("after-migration", "new.example", "new-source")));
                assertThat(fixture.number("SELECT MAX(id) FROM masks")).isGreaterThan(81);
                assertThat(fixture.number("SELECT COUNT(*) FROM masks_sources")).isEqualTo(82);
            }
        }
    }

    @Test
    void operationalRestartFinalizesNewCommittedDeliveryFromReceiptWithoutStage() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256); fixture.stageAndRegister();
                assertThatThrownBy(() -> operations.use(b -> new DataframeImportPromotionService(fixture.ledger, command -> {
                    b.imports().promote(command); throw new IllegalStateException("after canonical commit before ledger transition");
                }, StagedPublicationFixture.CLOCK).processNext())).hasMessageContaining("after canonical commit");
                assertThat(fixture.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.PROMOTING);
            }
            var expected = facts(fixture); deleteStage(root.resolve("stage"));
            try (var operations = JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration())) {
                operations.recoverPage(256);
                operations.use(b -> new DataframeImportPromotionService(fixture.ledger, b.imports(), StagedPublicationFixture.CLOCK).processNext());
                assertThat(fixture.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
                assertThat(facts(fixture)).isEqualTo(expected);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedConversionKeepsNativeTablesAndVersionThenAllowsCorrectedRetry(boolean combinedFields) throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            int size = combinedFields ? 524289 : 1048577;
            try (var connection = fixture.dataframe.getConnection(); var statement = connection.prepareStatement("UPDATE masks SET description=?,source=?")) {
                statement.setString(1, "x".repeat(size)); statement.setString(2, combinedFields ? "y".repeat(size) : "seed-source"); statement.executeUpdate();
            }
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service))
                    .hasRootCauseMessage("Native row exceeds staged migration byte limit");
            assertThat(fixture.number("PRAGMA user_version")).isEqualTo(12);
            assertThat(fixture.text("SELECT type FROM sqlite_schema WHERE name='masks'")).isEqualTo("table");
            assertThat(fixture.number("SELECT COUNT(*) FROM sqlite_schema WHERE name LIKE 'publication_%'")).isZero();
            assertThat(fixture.number("SELECT length(description) FROM masks")).isEqualTo(size);
            sql(fixture, "UPDATE masks SET description=NULL,source='seed-source'");
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                assertThat(operations.recoverPage(256)).isTrue();
                assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isOne();
            }
        }
    }

    @Test
    void migrationDoesNotRenewAnAlreadyExpiredLifecycle() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            fixture.backend.nativeConfirmation(fixture.dataframe, EffectiveTime.at(StagedPublicationFixture.NOW.minusSeconds(43200)), fixture.backend.readContext())
                    .confirm(fixture.document("old-observation", "seed.example", "old-source"));
            long oldId = fixture.number("SELECT id FROM masks");
            long oldLifecycle = fixture.number("SELECT _lifecycle_id FROM masks");
            var expected = facts(fixture);
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256);
                assertThat(facts(fixture)).isEqualTo(expected);
                assertThat(fixture.number("SELECT COUNT(*) FROM masks WHERE _valid_until_epoch_ms>" + StagedPublicationFixture.NOW.toEpochMilli())).isZero();
                operations.use(b -> b.confirmations().confirm(fixture.document("new-observation", "seed.example", "new-source")));
                assertThat(fixture.number("SELECT id FROM masks")).isGreaterThan(oldId);
                assertThat(fixture.number("SELECT _lifecycle_id FROM masks")).isGreaterThan(oldLifecycle);
                assertThat(fixture.number("SELECT former_row_id FROM masks_history")).isEqualTo(oldId);
            }
        }
    }

    @Test
    void migratesMoreThanOneTransportChunkWithoutLosingIdsOrProvenance() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            var first = fixture.document("bulk", "bulk-0.example", "bulk-source");
            var records = new java.util.ArrayList<com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation>();
            for (int index = 0; index < 300; index++) {
                fixture.document("bulk", "bulk-" + index + ".example", "bulk-source").records().forEach(records::add);
            }
            var command = new com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation(first.observationId(), first.sourceKey(),
                    first.receipt(), first.artifactName(), first.header(), records);
            fixture.backend.nativeConfirmation(fixture.dataframe, EffectiveTime.at(StagedPublicationFixture.NOW), fixture.backend.readContext()).confirm(command);
            var expected = facts(fixture);
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256);
                assertThat(facts(fixture)).isEqualTo(expected);
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_version_masks")).isEqualTo(300);
                assertThat(fixture.number("SELECT COUNT(*) FROM masks_sources")).isEqualTo(300);
            }
        }
    }

    @Test
    void preservesBurnedInternalRangesForArtifactsWithoutPublicIds() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeIdlessFixture(root)) {
            var old = idlessDocument(fixture, "old", "old.example");
            fixture.backend.nativeConfirmation(fixture.dataframe, EffectiveTime.at(StagedPublicationFixture.NOW), fixture.backend.readContext()).confirm(old);
            new JdbcPrivateIdReservations(fixture.dataframe, StagedPublicationFixture.CLOCK, fixture.backend.configuration().admission()).reserve("masks", 40);
            long next = fixture.number("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'");
            var expected = facts(fixture);
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256);
                assertThat(facts(fixture)).isEqualTo(expected);
                assertThat(fixture.number("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'")).isEqualTo(next);
                operations.use(b -> b.confirmations().confirm(idlessDocument(fixture, "new", "new.example")));
                assertThat(fixture.number("SELECT id FROM masks WHERE mask='new.example'")).isGreaterThanOrEqualTo(next);
                assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isEqualTo(2);
            }
        }
    }

    @Test
    void maintenanceFailureIsVisibleUntilASuccessfulBackstopPage() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root);
             var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
            operations.recoverPage(256);
            sql(fixture, "CREATE TRIGGER fail_maintenance BEFORE INSERT ON publication_cleanup_cursor BEGIN SELECT RAISE(ABORT,'maintenance fault'); END");
            assertThatThrownBy(() -> operations.maintenancePage(256)).hasRootCauseMessage("[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (maintenance fault)");
            assertThat(operations.health().ready()).isFalse();
            sql(fixture, "DROP TRIGGER fail_maintenance");
            operations.maintenancePage(256);
            assertThat(operations.health().ready()).isTrue();
        }
    }

    @Test
    void incompatiblePinsOrFormatKeepReadinessClosed() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256);
                sql(fixture, "UPDATE publication_configuration SET pins='different-policy'");
                assertThat(operations.health().ready()).isFalse();
            }
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration()))
                    .hasRootCauseMessage("Staged canonical configuration changed; drained migration required");
            sql(fixture, "PRAGMA user_version=14");
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration()))
                    .hasRootCauseMessage("Incompatible staged publication format");
        }
    }

    @Test
    void rejectsNativeIdentityDriftWithoutLeavingMigrationStructures() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            sql(fixture, "UPDATE artifact_identity SET identity_hash='drifted'");
            var expected = facts(fixture);
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service))
                    .hasRootCauseMessage("Native identity catalog differs from staged migration pins");
            assertThat(facts(fixture)).isEqualTo(expected);
            assertThat(fixture.number("PRAGMA user_version")).isEqualTo(12);
        }
    }

    @Test
    void refusesUntrustedCatalogIdentifiersAndPreservesLiteralSqlLikeFieldValues() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            fixture.backend.nativeConfirmation(fixture.dataframe, EffectiveTime.at(StagedPublicationFixture.NOW), fixture.backend.readContext())
                    .confirm(fixture.document("sql-trust", "safe.example", "source'; DROP TABLE masks;--"));
            sql(fixture, "ALTER TABLE masks ADD COLUMN \"untrusted;column\" TEXT");
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.number("PRAGMA user_version")).isEqualTo(12);
            sql(fixture, "ALTER TABLE masks DROP COLUMN \"untrusted;column\"");
            var expected = facts(fixture);
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256);
                assertThat(facts(fixture)).isEqualTo(expected);
                assertThat(fixture.text("SELECT source FROM masks")).isEqualTo("source'; DROP TABLE masks;--");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"extra-artifact", "missing-artifact", "identity-epoch", "allocator-missing", "allocator-strategy", "allocator-epoch", "lifecycle-disabled", "service-format"})
    void rejectsIncompatibleNativeCatalogWithoutPartialConversion(String kind) throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            switch (kind) {
                case "extra-artifact" -> sql(fixture, "INSERT INTO artifact_identity(artifact,identity_hash,epoch,applied_at) VALUES('unexpected','hash',1,'2026-08-24T12:00:00Z')");
                case "missing-artifact" -> sql(fixture, "DELETE FROM artifact_identity WHERE artifact='masks'");
                case "identity-epoch" -> sql(fixture, "UPDATE artifact_identity SET epoch=2 WHERE artifact='masks'");
                case "allocator-missing" -> sql(fixture, "DELETE FROM artifact_id_allocator WHERE artifact='masks'");
                case "allocator-strategy" -> sql(fixture, "UPDATE artifact_id_allocator SET strategy='DESCENDING' WHERE artifact='masks'");
                case "allocator-epoch" -> sql(fixture, "UPDATE artifact_id_allocator SET identity_epoch=2 WHERE artifact='masks'");
                case "lifecycle-disabled" -> sql(fixture, "UPDATE canonical_lifecycle_control SET state='DISABLED_COMPATIBLE',policy_fingerprint=NULL,activated_at_ms=NULL");
                case "service-format" -> serviceSql(fixture, "PRAGMA user_version=15");
                default -> throw new IllegalArgumentException(kind);
            }
            var expected = facts(fixture);
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service))
                    .isInstanceOf(com.iocextractor.common.IocExtractorException.class);
            assertThat(fixture.number("PRAGMA user_version")).isEqualTo(12);
            assertThat(fixture.number("SELECT COUNT(*) FROM sqlite_schema WHERE name LIKE 'publication_%'")).isZero();
            assertThat(facts(fixture)).isEqualTo(expected);
        }
    }

    @Test
    void refusesCrossProcessOwnershipUntilThePreviousProcessTerminates() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) { operations.recoverPage(256); }
            Path marker = root.resolve("leased");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                    System.getProperty("java.class.path"), StagedLeaseProbe.class.getName(), root.resolve("dataframe.db").toString(), marker.toString())
                    .redirectErrorStream(true).redirectOutput(root.resolve("lease-child.log").toFile()).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) { Thread.sleep(20); }
                assertThat(Files.exists(marker)).as("independent JVM acquired lease: %s", Files.readString(root.resolve("lease-child.log"))).isTrue();
                assertThatThrownBy(() -> JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration()))
                        .hasRootCauseMessage("Staged database is owned by another process");
            } finally {
                process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            try (var operations = JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration())) { assertThat(operations.recoverPage(256)).isTrue(); }
        }
    }

    @Test
    void memoryDatabaseAndSymlinkLeaseAreRejectedAndClosedScopesCannotAdmitWork() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertThatThrownBy(() -> JdbcStagedDatabaseLease.acquire(connection))
                    .hasMessageContaining("file-backed SQLite");
        }
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            Path linked = root.resolve("dataframe.db.staged-lease");
            Files.writeString(root.resolve("unowned"), "preserve"); Files.createSymbolicLink(linked, root.resolve("unowned"));
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service))
                    .isInstanceOf(com.iocextractor.common.IocExtractorException.class);
            assertThat(Files.readString(root.resolve("unowned"))).isEqualTo("preserve"); Files.delete(linked);
            var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service);
            try { operations.recoverPage(256); } finally { operations.close(); }
            assertThat(operations.health().ready()).isFalse();
            assertThatThrownBy(() -> operations.use(b -> b.snapshots())).hasMessageContaining("closed");
            operations.close();
        }
    }

    @Test
    void safeClockAndDurableMaintenanceCatalogErrorsKeepHealthClosed() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root);
             var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
            operations.recoverPage(256);
            sql(fixture, "UPDATE canonical_lifecycle_control SET safe_time_high_water_ms=" + StagedPublicationFixture.NOW.plusSeconds(600).toEpochMilli());
            assertThat(operations.health().ready()).isFalse();
            sql(fixture, "UPDATE canonical_lifecycle_control SET safe_time_high_water_ms=" + StagedPublicationFixture.NOW.toEpochMilli());
            sql(fixture, "INSERT INTO publication_cleanup_cursor VALUES('$rotation',10000)");
            assertThatThrownBy(() -> operations.maintenancePage(256)).hasRootCauseMessage("Publication maintenance catalog changed");
            assertThat(operations.health().ready()).isFalse();
            sql(fixture, "UPDATE publication_cleanup_cursor SET position=0 WHERE family='$rotation'");
            operations.maintenancePage(256); assertThat(operations.health().ready()).isTrue();
        }
    }

    @Test
    void healthDoesNotBorrowASecondConnectionFromASingleConnectionPool() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root);
             var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
            operations.recoverPage(256);
            fixture.dataframe.setMinimumIdle(1); fixture.dataframe.setMaximumPoolSize(1);
            fixture.dataframe.getHikariPoolMXBean().softEvictConnections();
            assertThat(operations.health().ready()).isTrue();
            assertThat(fixture.dataframe.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"import", "ingest", "export"})
    void refusesUndrainedCoordinationBeforeAnyCanonicalChange(String kind) throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            switch (kind) {
                case "import" -> fixture.stageAndRegister();
                case "ingest" -> serviceSql(fixture, "INSERT INTO ingestion_ledger(observation_id,source_key,status,original_path,processing_path,detected_at,updated_at)"
                        + " VALUES('not-drained','source','CLAIMED','source.docx','owned.docx','2026-08-24T12:00:00Z','2026-08-24T12:00:00Z')");
                case "export" -> serviceSql(fixture, "INSERT INTO export_run(run_id,profile,status,slice_name,plan_hash,started_at,updated_at)"
                        + " VALUES('not-drained','profile','STARTED','slice','" + "a".repeat(64) + "','2026-08-24T12:00:00Z','2026-08-24T12:00:00Z')");
                default -> throw new IllegalArgumentException(kind);
            }
            var expected = facts(fixture);
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service))
                    .hasRootCauseMessage("Staged migration requires drained " + switch (kind) {
                        case "import" -> "import_delivery"; case "ingest" -> "ingestion_ledger"; default -> "export_run";
                    });
            assertThat(fixture.number("PRAGMA user_version")).isEqualTo(12);
            assertThat(facts(fixture)).isEqualTo(expected);
        }
    }

    @Test
    void exclusiveLeaseBlocksAnotherOwnerAndShutdownDoesNotReleaseBeneathLiveWork() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root);
             var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
            operations.recoverPage(256);
            assertThatThrownBy(() -> JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration()))
                    .hasRootCauseInstanceOf(java.nio.channels.OverlappingFileLockException.class);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> operations.use(b -> {
                    entered.countDown(); await(release); return b.configuration().fingerprint();
                }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(operations::close).hasMessageContaining("drain");
                assertThatThrownBy(() -> JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration()))
                        .hasRootCauseInstanceOf(java.nio.channels.OverlappingFileLockException.class);
                release.countDown(); assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(fixture.backend.configuration().fingerprint());
            } finally {
                release.countDown(); worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void restartsWithBoundedCancellationPagesAndPreservesCommittedFacts() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256);
            }
            var expected = facts(fixture);
            var transactions = new JdbcPublicationTransactions(fixture.dataframe, fixture.backend.configuration().admission());
            transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> {
                var protocol = new JdbcPublicationProtocol();
                for (int index = 0; index < 257; index++) {
                    protocol.begin(c, "control:abandoned-" + index, "unchanged-pins", new JdbcPublicationProtocol.Manifest(0, 0, Long.MAX_VALUE, Map.of()));
                }
                var owner = new JdbcPublicationOwner(); owner.acquire(c, "control:abandoned-0", owner.fence(c));
                return null;
            });
            try (var operations = JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration())) {
                assertThat(operations.health().pendingRecovery()).isTrue();
                assertThatThrownBy(() -> operations.use(b -> b.snapshots())).hasMessageContaining("recovery");
                assertThat(operations.recoverPage(128)).isFalse();
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit WHERE state='PENDING'")).isEqualTo(129);
                assertThat(operations.recoverPage(128)).isFalse();
            }
            try (var operations = JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration())) {
                assertThat(operations.recoverPage(128)).isTrue();
                assertThat(operations.health().ready()).isTrue();
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_owner_failure WHERE error_code='PUBLICATION.PROCESS_RESTART'")).isOne();
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_logical_owner WHERE unit IS NOT NULL")).isZero();
                assertThat(facts(fixture)).isEqualTo(expected);
            }
        }
    }

    @Test
    void maintenanceRotationAndKeysetProgressSurviveReopenWithoutChangingReceiptOrVisibility() throws Exception {
        try (var fixture = StagedPublicationFixture.nativeFixture(root)) {
            seedNative(fixture);
            String first;
            try (var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
                operations.recoverPage(256);
                for (int index = 0; index < 5; index++) {
                    operations.use(b -> b.projections().recordFailure("masks", b.projections().load("masks").requiredGeneration(), "TEST.FAILURE"));
                }
                first = operations.maintenancePage(4).family();
            }
            var expected = facts(fixture);
            try (var operations = JdbcStagedCanonicalOperations.reopen(fixture.backend.configuration())) {
                operations.recoverPage(256);
                assertThat(operations.maintenancePage(4).family()).isNotEqualTo(first);
                for (int index = 0; index < 300; index++) {
                    var result = operations.maintenancePage(4);
                    assertThat(result.page().scanned()).isLessThanOrEqualTo(4);
                    assertThat(result.page().removed()).isLessThanOrEqualTo(4);
                }
                assertThat(facts(fixture)).isEqualTo(expected);
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit WHERE identity LIKE 'control:%'")).isEqualTo(1);
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit WHERE identity='migration:native-v12'")).isOne();
                assertThat(operations.health().ready()).isTrue();
            }
        }
    }

    @Test
    void restoresBothStoppedDatabasesAndOwnedFilesBeforeRunningNativeBinaryAgain() throws Exception {
        Path state = root.resolve("state"), backup = root.resolve("backup");
        Map<String, List<List<Object>>> expected;
        try (var fixture = StagedPublicationFixture.nativeFixture(state)) {
            seedNative(fixture);
            fixture.stageAndRegister();
            new DataframeImportPromotionService(fixture.ledger,
                    fixture.backend.nativeImport(fixture.dataframe, EffectiveTime.at(StagedPublicationFixture.NOW), fixture.backend.readContext()), StagedPublicationFixture.CLOCK).processNext();
            finish(fixture); expected = facts(fixture);
            Files.createDirectories(state.resolve("owned")); Files.writeString(state.resolve("owned/pins"), "pinned-owned-file");
        }
        copyTree(state, backup);
        try (var fixture = StagedPublicationFixture.nativeFixture(state, false);
             var operations = JdbcStagedCanonicalOperations.migrateDrained(fixture.backend.configuration(), fixture.service)) {
            operations.recoverPage(256);
            operations.use(b -> b.confirmations().confirm(fixture.document("new-after-upgrade", "new.example", "new-source")));
            Files.writeString(state.resolve("owned/pins"), "changed-by-candidate");
        }
        deleteStage(state); copyTree(backup, state);
        try (var restored = StagedPublicationFixture.nativeFixture(state, false)) {
            assertThat(restored.number("PRAGMA user_version")).isEqualTo(12);
            assertThat(new SqliteUserVersionSchemaMigrator(restored.dataframe, DataframeFormatMigrations.sqlite()).migrate().appliedVersions()).isEmpty();
            assertThat(facts(restored)).isEqualTo(expected);
            assertThat(Files.readString(state.resolve("owned/pins"))).isEqualTo("pinned-owned-file");
            assertThat(restored.ledger.findRecoverable(10)).isEmpty();
            assertThat(restored.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().terminalOutcome()).contains(ImportTerminalOutcome.SUCCEEDED);
        }
    }

    private static void seedNative(StagedPublicationFixture fixture) {
        fixture.backend.nativeConfirmation(fixture.dataframe, EffectiveTime.at(StagedPublicationFixture.NOW), fixture.backend.readContext())
                .confirm(fixture.document("seed", "seed.example", "seed-source"));
    }
    private static com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation idlessDocument(
            StagedPublicationFixture fixture, String observation, String value) {
        var fields = new LinkedHashMap<String, String>(); fields.put("mask", value); fields.put("source", "source"); fields.put("description", null);
        var row = com.iocextractor.application.artifact.ArtifactRow.ordered(fields);
        var key = new com.iocextractor.application.artifact.CanonicalArtifactKeyResolver(fixture.identities).recordKeyOf("masks", row).orElseThrow();
        var base = fixture.document(observation, value, "source");
        return new com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation(base.observationId(), base.sourceKey(), base.receipt(),
                "masks", fixture.schemas.getFirst().columns().stream().map(DataframeColumn::name).toList(),
                List.of(new com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation(
                        new com.iocextractor.application.artifact.ArtifactRowKey(key.keyHash()),
                        new com.iocextractor.application.artifact.PreparedArtifactRow(row, Optional.empty()))));
    }
    private static Map<String, List<List<Object>>> facts(StagedPublicationFixture fixture) throws SQLException {
        var result = new LinkedHashMap<String, List<List<Object>>>();
        for (var table : fixture.backend.store().tables()) { result.put(table.name(), fixture.facts(table.name())); }
        return result;
    }
    private static void finish(StagedPublicationFixture fixture) {
        for (var state : List.of(ImportDeliveryState.FINALIZING, ImportDeliveryState.TERMINAL)) {
            var delivery = fixture.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow();
            assertThat(fixture.ledger.transition(new ImportDeliveryTransition(delivery.id(), delivery.state(), delivery.version(), state,
                    state == ImportDeliveryState.TERMINAL ? Optional.of(ImportTerminalOutcome.SUCCEEDED) : Optional.empty(), StagedPublicationFixture.NOW)).name())
                    .isEqualTo("APPLIED");
        }
    }
    private static void sql(StagedPublicationFixture fixture, String sql) throws SQLException {
        try (var connection = fixture.dataframe.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static void serviceSql(StagedPublicationFixture fixture, String sql) throws SQLException {
        try (var connection = fixture.service.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Worker coordination timed out"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static void copyTree(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (var path : paths.toList()) {
                var target = to.resolve(from.relativize(path));
                if (Files.isDirectory(path)) { Files.createDirectories(target); } else { Files.copy(path, target); }
            }
        }
    }
    private static void deleteStage(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
    }
}
