package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.common.IocExtractorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Native staged publication under process loss, cancellation and competing workers. */
@IntegrationTest
@Timeout(45)
class StagedPublicationFaultIT {
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"PREPARED,0", "BEFORE_HIDDEN_CHUNK,0", "HIDDEN_CHUNK,0", "BEFORE_HIDDEN_CHUNK,1",
            "HIDDEN_CHUNK,1", "BEFORE_HIDDEN_CHUNK,2", "HIDDEN_CHUNK,2", "SEALED,0", "BEFORE_PUBLICATION,0", "PUBLICATION_STAGED,0", "COMMITTED,0"})
    void processLossReopensCommittedVisibilityAndRecoversTheRealServiceLedger(String phase, int chunk) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("crashed"));
        killAt(root, phase, chunk);

        try (var reopened = new StagedPublicationFixture(root, false, JdbcPublicationObserver.NOOP)) {
            boolean committed = phase.equals("COMMITTED");
            assertThat(reopened.number("SELECT COUNT(*) FROM masks")).isEqualTo(committed ? StagedPublicationFixture.ROWS + 1 : 1);
            assertThat(reopened.number("SELECT COUNT(*) FROM hashes")).isEqualTo(committed ? StagedPublicationFixture.ROWS : 0);
            assertThat(reopened.number("SELECT COUNT(*) FROM import_commit")).isEqualTo(committed ? 1 : 0);
            assertThat(reopened.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.PROMOTING);
            var before = committed ? allFacts(reopened) : null;
            if (committed) {
                var stage = new ImportWorkspaceLayout(root.resolve("stage")).paths(StagedPublicationFixture.DELIVERY).sealed();
                Files.delete(stage);
                assertThat(Files.exists(stage)).isFalse();
            }
            reopened.promoteHead();
            assertThat(reopened.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
            assertThat(reopened.number("SELECT COUNT(*) FROM masks")).isEqualTo(StagedPublicationFixture.ROWS + 1);
            assertThat(reopened.number("SELECT COUNT(*) FROM hashes")).isEqualTo(StagedPublicationFixture.ROWS);
            assertThat(reopened.number("SELECT COUNT(*) FROM import_commit")).isOne();
            assertThat(reopened.number("SELECT (SELECT COUNT(*) FROM masks_sources)+(SELECT COUNT(*) FROM hashes_sources)")).isEqualTo(2 * StagedPublicationFixture.ROWS + 1);
            assertThat(reopened.number("SELECT COUNT(*) FROM canonical_match_alias")).isEqualTo(2 * StagedPublicationFixture.ROWS + 1);
            assertThat(reopened.number("SELECT COUNT(DISTINCT slot) FROM export_slot_assignment WHERE profile='fault-profile' AND artifact='masks'"))
                    .isEqualTo(StagedPublicationFixture.ROWS + 1);
            assertThat(reopened.number("SELECT COUNT(*) FROM publication_logical_owner WHERE unit IS NOT NULL")).isZero();
            if (committed) { assertThat(allFacts(reopened)).as("receipt-only recovery leaves every canonical fact unchanged").isEqualTo(before); }
            else { assertThat(reopened.number("SELECT COUNT(*) FROM publication_owner_failure")).isOne(); }
            reopened.promoteHead();
            assertThat(reopened.number("SELECT COUNT(*) FROM import_commit")).isOne();
        }
    }

    @ParameterizedTest
    @EnumSource(value = JdbcPublicationObserver.Phase.class,
            names = {"BEFORE_HIDDEN_CHUNK", "HIDDEN_CHUNK", "BEFORE_PUBLICATION", "PUBLICATION_STAGED"})
    void interruptionBeforePublicationCancelsHiddenFactsAndReleasesLogicalOwnership(JdbcPublicationObserver.Phase boundary) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("interrupted"));
        var armed = new AtomicBoolean();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (armed.get() && phase == boundary) { Thread.currentThread().interrupt(); }
        })) {
            fixture.seed(); fixture.stageAndRegister(); armed.set(true);
            try { assertThatThrownBy(fixture::promoteHead).isInstanceOf(IocExtractorException.class); }
            finally { assertThat(Thread.interrupted()).as("cancellation flag preserved").isTrue(); }
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isZero();
            assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isOne();
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_logical_owner WHERE unit IS NOT NULL")).isZero();
            assertThat(fixture.text("SELECT state FROM publication_unit WHERE identity='import:fault-delivery'")).isEqualTo("CANCELLED");
            armed.set(false); fixture.promoteHead();
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isOne();
        }
    }

    @Test
    void anOldWorkerCannotPublishOrCancelTheReplacementAttempt() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("fenced"));
        var ready = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (armed.get() && phase == JdbcPublicationObserver.Phase.SEALED) {
                ready.countDown(); await(resume);
            }
        }); var workers = Executors.newSingleThreadExecutor()) {
            fixture.seed(); var command = fixture.stageAndRegister(); armed.set(true);
            Future<?> obsolete = workers.submit(() -> fixture.backend.imports().promote(command));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                try (var replacement = new StagedPublicationFixture(root, false, JdbcPublicationObserver.NOOP)) {
                    replacement.backend.imports().promote(command);
                    var committed = allFacts(replacement);
                    resume.countDown();
                    assertThatThrownBy(() -> obsolete.get(10, TimeUnit.SECONDS)).hasRootCauseMessage("Stale logical promotion owner");
                    assertThat(allFacts(replacement)).isEqualTo(committed);
                    assertThat(replacement.text("SELECT state FROM publication_unit WHERE identity='import:fault-delivery'")).isEqualTo("COMMITTED");
                }
            } finally { resume.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        }
    }

    @ParameterizedTest
    @EnumSource(value = JdbcPublicationObserver.Phase.class,
            names = {"BEFORE_PUBLICATION", "PUBLICATION_STAGED"})
    void interruptedControlPreservesPublicMetadataAndCancelsItsHiddenAttempt(JdbcPublicationObserver.Phase boundary) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("control-cancel"));
        var armed = new AtomicBoolean();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (armed.get() && phase == boundary) { Thread.currentThread().interrupt(); }
        })) {
            fixture.seed();
            var generation = fixture.backend.projections().load("masks").requiredGeneration();
            var before = allFacts(fixture);
            armed.set(true);
            try { assertThatThrownBy(() -> fixture.backend.projections().recordFailure("masks", generation, "PROJECTION.FAILED"))
                    .isInstanceOf(IocExtractorException.class); }
            finally { assertThat(Thread.interrupted()).isTrue(); }
            assertThat(allFacts(fixture)).isEqualTo(before);
            assertThat(fixture.text("SELECT state FROM publication_unit WHERE identity LIKE 'control:%'")).isEqualTo("CANCELLED");
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_logical_owner WHERE unit IS NOT NULL")).isZero();
            armed.set(false);
            assertThat(fixture.backend.projections().recordFailure("masks", generation, "PROJECTION.FAILED")).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sql", "exception", "fatal"})
    void failureInsideTheVisibilityFlipRollsBackEveryFamilyAndSequence(String failureKind) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("flip"));
        var armed = new AtomicBoolean();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (armed.get() && phase == JdbcPublicationObserver.Phase.PUBLICATION_STAGED) {
                if (failureKind.equals("exception")) { throw new IllegalStateException("flip exception"); }
                if (failureKind.equals("fatal")) { throw new AssertionError("flip fatal failure"); }
            }
        })) {
            fixture.seed(); fixture.stageAndRegister();
            var before = allFacts(fixture); before.remove("artifact_id_allocator");
            long sequence = fixture.number("SELECT value FROM publication_sequence");
            long nextId = fixture.number("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'");
            if (failureKind.equals("sql")) {
                try (var connection = fixture.dataframe.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TRIGGER fail_epoch BEFORE UPDATE ON publication_epoch BEGIN SELECT RAISE(ABORT,'flip SQL failure'); END");
                }
            }
            armed.set(true);
            assertThatThrownBy(fixture::promoteHead).hasStackTraceContaining("flip");
            var after = allFacts(fixture); after.remove("artifact_id_allocator");
            assertThat(after).isEqualTo(before);
            assertThat(fixture.number("SELECT value FROM publication_sequence")).isEqualTo(sequence);
            assertThat(fixture.number("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'")).isGreaterThan(nextId);
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_logical_owner WHERE unit IS NOT NULL")).isZero();
            assertThat(fixture.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.PROMOTING);
            armed.set(false);
            if (failureKind.equals("sql")) {
                try (var connection = fixture.dataframe.getConnection(); var statement = connection.createStatement()) { statement.execute("DROP TRIGGER fail_epoch"); }
            }
            fixture.promoteHead();
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isOne();
        }
    }

    @Test
    void aSlotControlPublishedDuringPreparationForcesRebuildAndPreservesTheSurvivor() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("slot-control"));
        var paused = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean(); var rebuilds = new AtomicInteger();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
            if (phase == JdbcPublicationObserver.Phase.PREPARED && attempt == 1 && armed.getAndSet(false)) { paused.countDown(); await(resume); }
        }); var workers = Executors.newSingleThreadExecutor()) {
            fixture.seed(); fixture.stageAndRegister(); armed.set(true);
            var importWork = workers.submit(fixture::promoteHead);
            try {
                assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.snapshot()).hasSize(1);
                assertThat(fixture.number("SELECT slot FROM export_slot_assignment WHERE artifact='masks'")).isOne();
                resume.countDown(); importWork.get(15, TimeUnit.SECONDS);
                assertThat(rebuilds.get()).isOne();
                assertThat(fixture.number("SELECT a.slot FROM export_slot_assignment a JOIN masks m ON m._lifecycle_id=a.lifecycle_id WHERE m.mask='seed.example'"))
                        .isOne();
                assertThat(fixture.number("SELECT COUNT(*) FROM import_slot_resolution WHERE outcome='OCCUPIED_FALLBACK'"))
                        .isEqualTo(StagedPublicationFixture.ROWS);
                assertThat(fixture.number("SELECT MIN(slot) FROM export_slot_assignment WHERE artifact='masks' AND slot>1")).isEqualTo(2);
                assertThat(fixture.number("SELECT COUNT(DISTINCT slot) FROM export_slot_assignment WHERE artifact='masks'"))
                        .isEqualTo(StagedPublicationFixture.ROWS + 1);
            } finally { resume.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        }
    }

    @Test
    void pinnedReaderRetainsAllFamiliesWhilePublicationAndBoundedCleanupAdvance() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("pinned-reader"));
        try (var fixture = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP)) {
            fixture.seed(); fixture.snapshot(); fixture.stageAndRegister();
            try (var pinned = fixture.dataframe.getConnection()) {
                pinned.setAutoCommit(false);
                var before = new LinkedHashMap<String, List<List<Object>>>();
                for (var table : fixture.backend.store().tables()) { before.put(table.name(), StagedPublicationFixture.facts(pinned, table.name())); }
                fixture.promoteHead();
                for (var table : fixture.backend.store().tables()) {
                    int cleanup = fixture.backend.cleanup().purgeVersions(table.name(), 256);
                    assertThat(cleanup).isLessThanOrEqualTo(256);
                    assertThat(StagedPublicationFixture.facts(pinned, table.name())).as("pinned %s", table.name()).isEqualTo(before.get(table.name()));
                }
                assertThat(fixture.number("SELECT COUNT(*) FROM hashes")).isEqualTo(StagedPublicationFixture.ROWS);
                pinned.rollback();
                assertThat(StagedPublicationFixture.facts(pinned, "hashes")).hasSize(StagedPublicationFixture.ROWS);
            }
            try (var connection = fixture.dataframe.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).as("reader released; WAL checkpoint not busy").isZero();
            }
        }
    }

    @Test
    void documentAndImportKeepSharedRegistrationPrecedenceWhenCompletionIsReversed() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("ordered-origins"));
        var policy = new ArtifactWritePolicy(
                ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST, null,
                Map.of("source", ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
        try (var fixture = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, Map.of("masks", policy))) {
            var command = fixture.stageAndRegister();
            var registrations = new JdbcObservationRegistrationStore(fixture.dataframe, StagedPublicationFixture.CLOCK);
            var older = registrations.registerNew(new ObservationId(command.deliveryId().value()),
                    ObservationOrigin.MANAGED_IMPORT);
            var document = fixture.document("newer-document", "fault-0.example", "newer-source");
            var newer = registrations.registerNew(document.observationId(), ObservationOrigin.DOCUMENT);
            CanonicalRecordConfirmation orderedRecord;
            try (var cursor = document.records().open()) {
                assertThat(cursor.next()).isTrue();
                var record = cursor.value(); var prepared = record.preparedRow();
                orderedRecord = new CanonicalRecordConfirmation(record.rowKey(),
                        new PreparedArtifactRow(prepared.template(), prepared.idColumn(),
                        Map.of("source", new OccurrencePosition(0))));
            }
            document = new CanonicalArtifactConfirmation(document.observationId(), document.sourceKey(),
                    document.receipt(), document.artifactName(), document.header(), List.of(orderedRecord), newer);
            fixture.backend.confirmations().confirm(document);
            long canonicalId = fixture.number("SELECT id FROM masks WHERE mask='fault-0.example'");
            fixture.backend.imports().promote(new CanonicalImportCommand(command.deliveryId(),
                    command.sequence(), command.sourceId(), command.snapshot(), command.contract(), command.stage(), older));
            assertThat(fixture.text("SELECT source FROM masks WHERE mask='fault-0.example'")).isEqualTo("newer-source");
            assertThat(fixture.number("SELECT id FROM masks WHERE mask='fault-0.example'")).isEqualTo(canonicalId);
            assertThat(fixture.number("SELECT admission_order FROM canonical_lifecycle_field_origin WHERE field_name='source' AND lifecycle_id=1"))
                    .isEqualTo(newer.admissionOrder().value());
            assertThat(fixture.number("SELECT COUNT(*) FROM masks_sources WHERE row_id=" + canonicalId)).isEqualTo(2);
            assertThat(fixture.number("SELECT revision FROM artifact_revision WHERE artifact='masks'")).isEqualTo(2);
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isOne();
        }
    }

    @Test
    void aDifferentReadyPromotionCannotOvertakeAnOwnedUnit() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("ordered-publications"));
        var paused = new CountDownLatch(1); var resume = new CountDownLatch(1); var armed = new AtomicBoolean();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (armed.get() && phase == JdbcPublicationObserver.Phase.PREPARED) { paused.countDown(); await(resume); }
        }); var workers = Executors.newSingleThreadExecutor()) {
            fixture.seed(); fixture.stageAndRegister(); armed.set(true);
            var first = workers.submit(fixture::promoteHead);
            try {
                assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
                var next = fixture.document("next-document", "next.example", "next-source");
                assertThatThrownBy(() -> fixture.backend.confirmations().confirm(next)).hasRootCauseMessage("Logical promotion ownership changed or occupied");
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_owner_failure")).isZero();
                armed.set(false); resume.countDown(); first.get(15, TimeUnit.SECONDS);
                fixture.backend.confirmations().confirm(next);
                assertThat(fixture.number("SELECT COUNT(*) FROM masks WHERE mask='next.example'")).isOne();
                assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isOne();
            } finally { resume.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryWithMissingOrCorruptPrivateInputFailsClosedUntilPinnedBytesAreRestored(boolean corrupt) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("private-input"));
        killAt(root, "HIDDEN_CHUNK", 0);
        Path stage = new ImportWorkspaceLayout(root.resolve("stage")).paths(StagedPublicationFixture.DELIVERY).sealed();
        byte[] original = Files.readAllBytes(stage);
        if (corrupt) { Files.write(stage, new byte[] {1, 2, 3}); }
        else { Files.delete(stage); }
        try (var fixture = new StagedPublicationFixture(root, false, JdbcPublicationObserver.NOOP)) {
            assertThatThrownBy(fixture::promoteHead).isInstanceOf(IocExtractorException.class);
            assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isOne();
            assertThat(fixture.number("SELECT COUNT(*) FROM hashes")).isZero();
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isZero();
            assertThat(fixture.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.PROMOTING);
            Files.write(stage, original);
            fixture.promoteHead();
            assertThat(fixture.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isOne();
        }
    }

    private static void killAt(Path root, String phase, int chunk) throws Exception {
        Path log = root.resolve("child.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                StagedPublicationCrashProbe.class.getName(), root.toString(), phase, Integer.toString(chunk))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (process.isAlive() && !Files.exists(root.resolve("reached")) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertThat(Files.exists(root.resolve("reached"))).as("child boundary %s/%s; log: %s", phase, chunk, Files.readString(log)).isTrue();
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("owned child terminated").isTrue();
        }

    }

    private static LinkedHashMap<String, List<List<Object>>> allFacts(StagedPublicationFixture fixture) throws Exception {
        var result = new LinkedHashMap<String, List<List<Object>>>();
        for (var table : fixture.backend.store().tables()) { result.put(table.name(), fixture.facts(table.name())); }
        result.put("artifact_id_allocator", fixture.facts("artifact_id_allocator"));
        return result;
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Worker release timed out"); } }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
