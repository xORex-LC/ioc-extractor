package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.common.IocExtractorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import java.util.concurrent.atomic.AtomicReference;
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
            if (!committed) {
                try (var baseline = new StagedPublicationFixture(Files.createDirectory(temporary.resolve("seed-only")),
                        true, JdbcPublicationObserver.NOOP)) {
                    baseline.seed();
                    var expected = allFacts(baseline); expected.remove("artifact_id_allocator");
                    var actual = allFacts(reopened); actual.remove("artifact_id_allocator");
                    assertThat(actual).as("every native family exposes only the prior committed seed").isEqualTo(expected);
                }
            }
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
        var ready = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var replacementReady = new CountDownLatch(1); var replacementResume = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (armed.get() && phase == JdbcPublicationObserver.Phase.SEALED) { ready.countDown(); await(resume); }
        }); var replacement = new StagedPublicationFixture(root, false, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.SEALED) { replacementReady.countDown(); await(replacementResume); }
        }); var workers = Executors.newFixedThreadPool(2)) {
            fixture.seed(); var command = fixture.stageAndRegister(); armed.set(true);
            Future<?> obsolete = workers.submit(() -> fixture.backend.imports().promote(command));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> current = workers.submit(() -> replacement.backend.imports().promote(command));
                assertThat(replacementReady.await(10, TimeUnit.SECONDS)).isTrue();
                var before = allFacts(replacement);
                long fence = replacement.number("SELECT fence FROM publication_unit WHERE identity='import:fault-delivery'");
                resume.countDown();
                assertThatThrownBy(() -> obsolete.get(10, TimeUnit.SECONDS)).hasRootCauseMessage("Stale logical promotion owner");
                assertThat(replacement.text("SELECT state FROM publication_unit WHERE identity='import:fault-delivery'")).isEqualTo("SEALED");
                assertThat(replacement.number("SELECT fence FROM publication_unit WHERE identity='import:fault-delivery'")).isEqualTo(fence);
                assertThat(allFacts(replacement)).as("obsolete worker cannot cancel or mutate the live replacement").isEqualTo(before);
                replacementResume.countDown(); current.get(10, TimeUnit.SECONDS);
                assertThat(replacement.text("SELECT state FROM publication_unit WHERE identity='import:fault-delivery'")).isEqualTo("COMMITTED");
                assertThat(replacement.number("SELECT COUNT(*) FROM import_commit")).isOne();
            } finally {
                resume.countDown(); replacementResume.countDown(); workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
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
    void expiryAndSlotReconciliationDuringPreparationCannotResurrectTheOldLifecycle() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("expired-slots"));
        var wall = new ControlledClock();
        var paused = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean(); var rebuilds = new AtomicInteger();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
            if (phase == JdbcPublicationObserver.Phase.PREPARED && armed.getAndSet(false)) { paused.countDown(); await(resume); }
        }, Map.of(), wall); var workers = Executors.newSingleThreadExecutor()) {
            fixture.backend.confirmations().confirm(fixture.document("old-lifecycle", "fault-0.example", "fault-feed"));
            long oldId = fixture.number("SELECT id FROM masks");
            long oldLifecycle = fixture.number("SELECT _lifecycle_id FROM masks");
            fixture.snapshot(); fixture.stageAndRegister();
            wall.set(StagedPublicationFixture.NOW.plus(Duration.ofHours(12)).minusMillis(1));
            armed.set(true);
            var importWork = workers.submit(fixture::promoteHead);
            try {
                assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
                var equality = StagedPublicationFixture.NOW.plus(Duration.ofHours(12)); wall.set(equality);
                assertThat(fixture.backend.expiry().expireDue("masks", EffectiveTime.at(equality), 1).expired()).isOne();
                assertThat(fixture.snapshot()).isEmpty();
                assertThat(fixture.number("SELECT COUNT(*) FROM export_slot_assignment")).isZero();
                resume.countDown(); importWork.get(15, TimeUnit.SECONDS);
                assertThat(rebuilds.get()).isOne();
                assertThat(fixture.number("SELECT id FROM masks WHERE mask='fault-0.example'")).isGreaterThan(oldId);
                long newLifecycle = fixture.number("SELECT _lifecycle_id FROM masks WHERE mask='fault-0.example'");
                assertThat(newLifecycle).isGreaterThan(oldLifecycle);
                assertThat(fixture.number("SELECT COUNT(*) FROM export_slot_assignment WHERE lifecycle_id=" + oldLifecycle)).isZero();
                assertThat(fixture.number("SELECT slot FROM export_slot_assignment WHERE lifecycle_id=" + newLifecycle)).isOne();
                assertThat(fixture.number("SELECT _valid_until_epoch_ms FROM masks WHERE mask='fault-0.example'"))
                        .isEqualTo(equality.plus(Duration.ofHours(12)).toEpochMilli());
                assertThat(fixture.number("SELECT effective_as_of_ms FROM import_commit")).isEqualTo(equality.toEpochMilli());
                assertThat(fixture.number("SELECT COUNT(*) FROM masks_history WHERE former_row_id=" + oldId)).isOne();
                assertThat(fixture.number("SELECT COUNT(*) FROM canonical_match_alias WHERE lifecycle_id=" + oldLifecycle)).isZero();
            } finally { resume.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        }
    }

    @Test
    void metadataOnlyOriginAndRenewalOnlyObservationsPreserveRevisionAndGeneration() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("metadata-renewal"));
        var wall = new ControlledClock(); var advance = new AtomicBoolean();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.BEFORE_PUBLICATION && advance.getAndSet(false)) {
                wall.set(StagedPublicationFixture.NOW.plusSeconds(5));
            }
        }, Map.of(), wall)) {
            var registrations = new JdbcObservationRegistrationStore(fixture.dataframe, wall);
            var first = registrations.registerNew(new ObservationId("origin-one"), ObservationOrigin.DOCUMENT);
            fixture.backend.confirmations().confirm(fixture.document(first.observationId().value(), "metadata.example", "same-source", first));
            long rowId = fixture.number("SELECT id FROM masks"); long lifecycle = fixture.number("SELECT _lifecycle_id FROM masks");
            var second = registrations.registerNew(new ObservationId("origin-two"), ObservationOrigin.DOCUMENT);
            advance.set(true);
            var metadata = fixture.backend.confirmations().confirm(fixture.document(second.observationId().value(), "metadata.example", "same-source", second));
            assertThat(metadata.metadataOnlyRows()).isOne(); assertThat(metadata.publicRowsChanged()).isZero();
            assertThat(metadata.artifactRevision()).isOne(); assertThat(metadata.requiredProjectionGeneration().value()).isOne();
            assertThat(fixture.number("SELECT admission_order FROM canonical_lifecycle_field_origin WHERE field_name='source'"))
                    .isEqualTo(second.admissionOrder().value());
            assertThat(fixture.number("SELECT _valid_until_epoch_ms FROM masks"))
                    .isEqualTo(StagedPublicationFixture.NOW.plus(Duration.ofHours(12)).plusSeconds(5).toEpochMilli());
            wall.set(StagedPublicationFixture.NOW.plusSeconds(10));
            var renewal = fixture.backend.confirmations().confirm(fixture.document("renew-only", "metadata.example", "same-source"));
            assertThat(renewal.renewed()).isOne(); assertThat(renewal.metadataOnlyRows()).isZero();
            assertThat(renewal.artifactRevision()).isOne(); assertThat(renewal.requiredProjectionGeneration().value()).isOne();
            assertThat(fixture.number("SELECT id FROM masks")).isEqualTo(rowId);
            assertThat(fixture.number("SELECT _lifecycle_id FROM masks")).isEqualTo(lifecycle);
            assertThat(fixture.number("SELECT _valid_until_epoch_ms FROM masks"))
                    .isEqualTo(StagedPublicationFixture.NOW.plus(Duration.ofHours(12)).plusSeconds(10).toEpochMilli());
            assertThat(fixture.number("SELECT COUNT(*) FROM canonical_observation_commit")).isEqualTo(3);
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
            document = fixture.document(document.observationId().value(), "fault-0.example", "newer-source", newer);
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

    private static final class ControlledClock extends Clock {
        private final AtomicReference<Instant> utc =
                new AtomicReference<>(StagedPublicationFixture.NOW);
        void set(Instant value) { utc.set(value); }
        @Override public Instant instant() { return utc.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
