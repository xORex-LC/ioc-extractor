package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Attempt;
import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Fact;
import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Manifest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission.OperationClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Physical publication qualification only; does not implement or replace canonical ports. */
@IntegrationTest
@Timeout(30)
class SqlitePublicationExperimentIT {
    private static final long NOW = 1_800_000_000_000L;
    private static final String PINS = "schema:12/catalog:fixture/policy:fixture/digest:fixture";
    @TempDir Path directory;

    @Test
    void publishesAllFamiliesTogetherWhilePinnedReadersRetainTheirSnapshot() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt base = fixture.begin("base", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(base, 0, List.of(new Fact("row", "masks", "canonical:1", "old")));
            fixture.seal(base);
            fixture.publish(base, NOW, "base-receipt");

            try (Connection reader = fixture.dataSource.getConnection()) {
                reader.setAutoCommit(false);
                assertThat(fixture.probe.read(reader, "row", "masks", "canonical:1")).contains("old");
                List<Fact> first = List.of(new Fact("row", "masks", "canonical:1", "new"),
                        new Fact("alias", "masks", "match:host", "canonical:1"),
                        new Fact("source", "masks", "canonical:1/source:A", "occurrences:2"),
                        new Fact("origin", "masks", "canonical:1/name", "rank:10"));
                List<Fact> second = List.of(new Fact("row", "hashes", "canonical:2", "hash-value"),
                        new Fact("revision", "masks", "current", "2"),
                        new Fact("slot", "reputation/masks", "7", "lifecycle:1"),
                        new Fact("warning", "masks", "row:2", "IMPORT.VIEW_FALLBACK"));
                Attempt unit = fixture.begin("delivery", 2, 8, NOW + 1,
                        Map.of("masks", 1L, "hashes", 0L, "reputation/masks", 0L));
                fixture.append(unit, 0, first);
                fixture.append(unit, 1, second);
                assertThat(fixture.read("row", "masks", "canonical:1")).contains("old");
                assertThat(fixture.read("row", "hashes", "canonical:2")).isEmpty();
                assertThat(fixture.receipt(unit)).isEmpty();
                fixture.seal(unit);
                var receipt = fixture.publish(unit, NOW, "delivery-receipt/accepted:1/rejected:0");

                assertThat(receipt.sequence()).isEqualTo(2);
                assertThat(fixture.probe.read(reader, "row", "masks", "canonical:1")).contains("old");
                assertThat(fixture.probe.receipt(reader, unit.unitId())).isEmpty();
                for (Fact fact : java.util.stream.Stream.concat(first.stream(), second.stream()).toList()) {
                    assertThat(fixture.read(fact.family(), fact.scope(), fact.key())).contains(fact.payload());
                }
                reader.rollback();
                assertThat(fixture.probe.read(reader, "row", "masks", "canonical:1")).contains("new");
                reader.rollback();
                assertThat(fixture.publish(unit, NOW + 500, receipt.value())).isEqualTo(receipt);
                assertThat(fixture.receipt(unit)).contains(receipt);
            }
        }
    }

    @Test
    void fencesRebuildsAndRejectsChangedPinsWithoutExposingOldAttemptFacts() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt old = fixture.begin("delivery", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(old, 0, List.of(new Fact("row", "masks", "old-attempt", "discard")));
            fixture.seal(old);
            assertThatThrownBy(() -> fixture.write(OperationClass.PROMOTION,
                    c -> fixture.probe.rebuild(c, old, "different", new Manifest(1, 1, Long.MAX_VALUE, Map.of("masks", 0L)))))
                    .hasRootCauseMessage("Stale attempt or changed pins");
            Attempt fresh = fixture.write(OperationClass.PROMOTION,
                    c -> fixture.probe.rebuild(c, old, PINS, new Manifest(1, 2, Long.MAX_VALUE, Map.of("masks", 0L))));
            assertThat(fresh.unitId()).isEqualTo(old.unitId());
            assertThat(fresh.fence()).isGreaterThan(old.fence());
            assertThat(fresh.stamp()).isGreaterThan(old.stamp());
            assertThatThrownBy(() -> fixture.append(old, 0,
                    List.of(new Fact("row", "masks", "old-attempt", "discard"))))
                    .hasRootCauseMessage("Stale attempt or invalid publication state");
            assertThatThrownBy(() -> fixture.publish(old, NOW, "stale-receipt"))
                    .hasRootCauseMessage("Stale attempt or invalid publication state");
            assertThatThrownBy(() -> fixture.write(OperationClass.PROMOTION, c -> {
                fixture.probe.cancel(c, old); return null;
            })).hasRootCauseMessage("Incomplete, expired or stale publication attempt");
            fixture.append(fresh, 0, List.of(new Fact("row", "masks", "fresh-attempt", "kept"),
                    new Fact("history", "masks", "expired-lifecycle", "archived")));
            fixture.seal(fresh);
            fixture.publish(fresh, NOW, "fresh-receipt");
            assertThat(fixture.read("row", "masks", "old-attempt")).isEmpty();
            assertThat(fixture.read("row", "masks", "fresh-attempt")).contains("kept");
        }
    }

    @Test
    void rejectsIncompleteGappedChangedAndOversizedChunksAndRequiresDeclaredScopes() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt unit = fixture.begin("delivery", 2, 2, Long.MAX_VALUE, Map.of("masks", 0L));
            Fact fact = new Fact("row", "masks", "key", "value");
            assertThatThrownBy(() -> fixture.seal(unit))
                    .hasRootCauseMessage("Incomplete, expired or stale publication attempt");
            assertThatThrownBy(() -> fixture.append(unit, 1, List.of(fact)))
                    .hasRootCauseMessage("Incomplete, expired or stale publication attempt");
            fixture.append(unit, 0, List.of(fact));
            fixture.append(unit, 0, List.of(fact));
            assertThatThrownBy(() -> fixture.append(unit, 0,
                    List.of(new Fact("row", "masks", "key", "changed"))))
                    .hasRootCauseMessage("Changed chunk retry");
            assertThatThrownBy(() -> fixture.append(unit, 1,
                    List.of(new Fact("row", "undeclared", "key", "value"))))
                    .hasRootCauseInstanceOf(SQLException.class);
            assertThatThrownBy(() -> fixture.append(unit, 1,
                    List.of(new Fact("row", "masks", "large", "x".repeat(1024 * 1024)))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.append(unit, 1,
                    List.of(fact, new Fact("row", "masks", "over-manifest", "value"))))
                    .hasRootCauseInstanceOf(SQLException.class);
            fixture.append(unit, 1, List.of(new Fact("row", "masks", "other", "value")));
            fixture.seal(unit);
            assertThatThrownBy(() -> fixture.append(unit, 1, List.of(fact)))
                    .hasRootCauseMessage("Stale attempt or invalid publication state");
            fixture.publish(unit, NOW, "receipt");
            assertThat(fixture.read("row", "masks", "key")).contains("value");
            assertThat(fixture.read("row", "undeclared", "key")).isEmpty();
        }
    }

    @Test
    void rejectsExpiryEqualityAndSemanticEpochChangesWithoutLeakingReceiptOrSequence() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt unit = fixture.begin("delivery", 1, 1, NOW, Map.of("masks", 0L));
            fixture.append(unit, 0, List.of(new Fact("row", "masks", "key", "pending")));
            fixture.seal(unit);
            assertThatThrownBy(() -> fixture.publish(unit, NOW, "receipt"))
                    .hasRootCauseMessage("Incomplete, expired or stale publication attempt");
            assertThat(fixture.receipt(unit)).isEmpty();
            assertThat(fixture.read("row", "masks", "key")).isEmpty();
            Attempt fresh = fixture.write(OperationClass.PROMOTION,
                    c -> fixture.probe.rebuild(c, unit, PINS, new Manifest(1, 1, NOW + 1, Map.of("masks", 0L))));
            fixture.append(fresh, 0, List.of(new Fact("row", "masks", "key", "fresh")));
            fixture.seal(fresh);
            fixture.write(OperationClass.EXPIRY, c -> { fixture.probe.advanceEpoch(c, "masks"); return null; });
            assertThatThrownBy(() -> fixture.publish(fresh, NOW, "receipt"))
                    .hasRootCauseMessage("Dependency changed; rebuild required");
            Attempt rebuilt = fixture.write(OperationClass.PROMOTION,
                    c -> fixture.probe.rebuild(c, fresh, PINS, new Manifest(1, 1, NOW + 1, Map.of("masks", 1L))));
            fixture.append(rebuilt, 0, List.of(new Fact("row", "masks", "key", "rebuilt")));
            fixture.seal(rebuilt);
            assertThat(fixture.publish(rebuilt, NOW, "receipt").sequence()).isEqualTo(1);
            assertThat(fixture.read("row", "masks", "key")).contains("rebuilt");
            assertThatThrownBy(() -> fixture.publish(rebuilt, NOW, "wrong-receipt"))
                    .hasRootCauseMessage("Receipt identity mismatch");
        }
    }

    @Test
    void ordersByPublicationRatherThanCreationAndTombstonesDoNotRevealOlderValues() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt early = fixture.begin("early", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            Attempt late = fixture.begin("late", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(late, 0, List.of(new Fact("row", "masks", "key", "late")));
            fixture.seal(late);
            fixture.publish(late, NOW, "late-receipt");
            // A stale base must be rebuilt, even when an earlier unit finishes later.
            Attempt rebased = fixture.write(OperationClass.PROMOTION,
                    c -> fixture.probe.rebuild(c, early, PINS, new Manifest(1, 1, Long.MAX_VALUE, Map.of("masks", 1L))));
            fixture.append(rebased, 0, List.of(new Fact("row", "masks", "key", "early-rebased")));
            fixture.seal(rebased);
            assertThat(fixture.publish(rebased, NOW, "early-receipt").sequence()).isEqualTo(2);
            assertThat(fixture.read("row", "masks", "key")).contains("early-rebased");
            Attempt deletion = fixture.begin("delete", 1, 1, Long.MAX_VALUE, Map.of("masks", 2L));
            fixture.append(deletion, 0, List.of(new Fact("row", "masks", "key", null)));
            assertThat(fixture.read("row", "masks", "key")).contains("early-rebased");
            fixture.seal(deletion);
            fixture.publish(deletion, NOW, "delete-receipt");
            assertThat(fixture.read("row", "masks", "key")).isEmpty();
        }
    }

    @Test
    void rejectsFutureDependencyPinsWhichWouldInvalidateIndexedVersionOrder() throws Exception {
        try (var fixture = new Fixture(directory)) {
            assertThatThrownBy(() -> fixture.begin("future", 1, 1, Long.MAX_VALUE, Map.of("masks", 1L)))
                    .hasRootCauseMessage("Stale dependency snapshot");
            Attempt current = fixture.begin("current", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(current, 0, List.of(new Fact("row", "masks", "key", "value")));
            fixture.seal(current);
            fixture.publish(current, NOW, "receipt");
            assertThatThrownBy(() -> fixture.begin("stale", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L)))
                    .hasRootCauseMessage("Stale dependency snapshot");
            assertThat(fixture.read("row", "masks", "key")).contains("value");
        }
    }

    @Test
    void foldsPendingContributionsAndOldChunkReplayCannotOverwriteNewerState() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt unit = fixture.begin("delivery", 3, 3, Long.MAX_VALUE, Map.of("masks", 0L));
            Fact first = new Fact("source", "masks", "row:1/source:A", "occurrences:1");
            fixture.append(unit, 0, List.of(first));
            fixture.append(unit, 1, List.of(new Fact("source", "masks", "row:1/source:A", "occurrences:2")));
            fixture.append(unit, 0, List.of(first));
            try (Connection base = fixture.dataSource.getConnection();
                 Connection pending = fixture.dataSource.getConnection()) {
                base.setAutoCommit(false);
                assertThat(fixture.probe.readOwn(pending, base, unit, "source", "masks", "row:1/source:A"))
                        .contains("occurrences:2");
                base.rollback();
            }
            fixture.append(unit, 2, List.of(new Fact("source", "masks", "row:1/source:A", "occurrences:3")));
            fixture.seal(unit);
            fixture.publish(unit, NOW, "receipt");
            assertThat(fixture.read("source", "masks", "row:1/source:A")).contains("occurrences:3");
        }
    }

    @Test
    void slotNamespaceChangesInvalidateWhileUnrelatedNamespacesDoNot() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt unit = fixture.begin("delivery", 1, 1, Long.MAX_VALUE,
                    Map.of("masks", 0L, "reputation/masks", 0L));
            fixture.append(unit, 0, List.of(new Fact("slot", "reputation/masks", "7", "lifecycle:1")));
            fixture.seal(unit);
            fixture.write(OperationClass.EXPORT_SLOTS, c -> {
                fixture.probe.advanceEpoch(c, "other-profile/masks"); return null;
            });
            fixture.write(OperationClass.EXPORT_SLOTS, c -> {
                fixture.probe.advanceEpoch(c, "reputation/masks"); return null;
            });
            assertThatThrownBy(() -> fixture.publish(unit, NOW, "receipt"))
                    .hasRootCauseMessage("Dependency changed; rebuild required");
            assertThat(fixture.read("slot", "reputation/masks", "7")).isEmpty();
            Attempt rebuilt = fixture.write(OperationClass.PROMOTION, c -> fixture.probe.rebuild(c, unit, PINS,
                    new Manifest(1, 1, Long.MAX_VALUE, Map.of("masks", 0L, "reputation/masks", 1L))));
            fixture.append(rebuilt, 0, List.of(new Fact("slot", "reputation/masks", "8", "lifecycle:1")));
            fixture.seal(rebuilt);
            fixture.write(OperationClass.EXPORT_SLOTS, c -> {
                fixture.probe.advanceEpoch(c, "other-profile/masks"); return null;
            });
            fixture.publish(rebuilt, NOW, "receipt");
            assertThat(fixture.read("slot", "reputation/masks", "7")).isEmpty();
            assertThat(fixture.read("slot", "reputation/masks", "8")).contains("lifecycle:1");
        }
    }

    @Test
    void committedTombstonesAndOldAttemptVersionsDoNotRequireSortingRetainedHistory() throws Exception {
        try (var fixture = new Fixture(directory)) {
            for (int ordinal = 0; ordinal < 64; ordinal++) {
                Attempt unit = fixture.begin("history-" + ordinal, 1, 1, Long.MAX_VALUE,
                        Map.of("masks", (long) ordinal));
                fixture.append(unit, 0, List.of(new Fact("row", "masks", "key", "v-" + ordinal)));
                fixture.seal(unit);
                fixture.publish(unit, NOW, "receipt-" + ordinal);
            }
            Attempt pending = fixture.begin("pending", 1, 1, Long.MAX_VALUE, Map.of("masks", 64L));
            fixture.append(pending, 0, List.of(new Fact("row", "masks", "key", "pending")));
            Attempt rebuilt = fixture.write(OperationClass.PROMOTION, c -> fixture.probe.rebuild(c, pending, PINS,
                    new Manifest(1, 1, Long.MAX_VALUE, Map.of("masks", 64L))));
            fixture.append(rebuilt, 0, List.of(new Fact("row", "masks", "key", null)));
            assertThat(fixture.read("row", "masks", "key")).contains("v-63");
            fixture.seal(rebuilt);
            fixture.publish(rebuilt, NOW, "tombstone-receipt");
            assertThat(fixture.read("row", "masks", "key")).isEmpty();
        }
    }

    @Test
    void overlaysOwnPendingValuesAndClearsOnOnePinnedBaseWithoutLeakingToPublicReads() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt initial = fixture.begin("initial", 1, 3, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(initial, 0, List.of(new Fact("row", "masks", "update", "original"),
                    new Fact("row", "masks", "clear", "original"),
                    new Fact("row", "masks", "unchanged", "original")));
            fixture.seal(initial);
            fixture.publish(initial, NOW, "initial-receipt");
            try (Connection base = fixture.dataSource.getConnection();
                 Connection pending = fixture.dataSource.getConnection()) {
                base.setAutoCommit(false);
                assertThat(fixture.probe.read(base, "row", "masks", "update")).contains("original");
                Attempt next = fixture.begin("next", 1, 2, Long.MAX_VALUE, Map.of("masks", 1L));
                fixture.append(next, 0, List.of(new Fact("row", "masks", "update", "prepared"),
                        new Fact("row", "masks", "clear", null)));
                assertThat(fixture.probe.readOwn(pending, base, next, "row", "masks", "update"))
                        .contains("prepared");
                assertThat(fixture.probe.readOwn(pending, base, next, "row", "masks", "clear")).isEmpty();
                assertThat(fixture.probe.readOwn(pending, base, next, "row", "masks", "unchanged"))
                        .contains("original");
                assertThat(fixture.read("row", "masks", "update")).contains("original");
                assertThat(fixture.read("row", "masks", "clear")).contains("original");
                base.rollback();
            }
        }
    }

    @Test
    void rollsBackPublicationAndReopensReceiptWithoutAnyPreparationInput() throws Exception {
        Attempt unit;
        try (var fixture = new Fixture(directory)) {
            unit = fixture.begin("delivery", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(unit, 0, List.of(new Fact("row", "masks", "key", "value")));
            fixture.seal(unit);
            assertThatThrownBy(() -> fixture.write(OperationClass.PROMOTION, c -> {
                fixture.probe.publish(c, unit, NOW, "receipt");
                throw new SQLException("Injected pre-commit failure");
            })).hasRootCauseMessage("Injected pre-commit failure");
            assertThat(fixture.receipt(unit)).isEmpty();
            assertThat(fixture.read("row", "masks", "key")).isEmpty();
            assertThat(fixture.publish(unit, NOW, "receipt").sequence()).isEqualTo(1);
        }
        try (var fixture = new Fixture(directory, false)) {
            assertThat(fixture.receipt(unit)).contains(new SqlitePublicationExperiment.Receipt(1, NOW, "receipt"));
            assertThat(fixture.publish(unit, NOW + 10_000, "receipt").sequence()).isEqualTo(1);
            assertThat(fixture.read("row", "masks", "key")).contains("value");
        }
    }

    @Test
    void publishesAnEmptyOutcomeReceiptAndCancelledUnitsCannotPublish() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt empty = fixture.begin("empty-outcome", 0, 0, Long.MAX_VALUE, Map.of());
            fixture.seal(empty);
            assertThat(fixture.publish(empty, NOW, "accepted:0/changed:0").sequence()).isEqualTo(1);
            assertThat(fixture.receipt(empty)).contains(new SqlitePublicationExperiment.Receipt(
                    1, NOW, "accepted:0/changed:0"));
            Attempt cancelled = fixture.begin("cancelled", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(cancelled, 0, List.of(new Fact("row", "masks", "key", "discarded")));
            fixture.write(OperationClass.PROMOTION, c -> { fixture.probe.cancel(c, cancelled); return null; });
            assertThatThrownBy(() -> fixture.publish(cancelled, NOW, "invalid-receipt"))
                    .hasRootCauseMessage("Stale attempt or invalid publication state");
            assertThat(fixture.read("row", "masks", "key")).isEmpty();
            assertThat(fixture.receipt(cancelled)).isEmpty();
        }
    }

    @Test
    void releasesPhysicalAdmissionBetweenChunksForControlAndUsesSelectiveLookup() throws Exception {
        try (var fixture = new Fixture(directory)) {
            int total = SqlitePublicationExperiment.CHUNK_FACT_LIMIT * 16;
            Attempt unit = fixture.begin("bulk", 16, total, Long.MAX_VALUE, Map.of("masks", 0L));
            CountDownLatch firstChunk = new CountDownLatch(1);
            CountDownLatch controlFinished = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var worker = executor.submit(() -> {
                    for (int ordinal = 0; ordinal < 16; ordinal++) {
                        List<Fact> facts = new ArrayList<>();
                        for (int index = 0; index < SqlitePublicationExperiment.CHUNK_FACT_LIMIT; index++) {
                            facts.add(new Fact("row", "masks", "key-" + (ordinal * 256 + index), "value"));
                        }
                        fixture.append(unit, ordinal, facts);
                        if (ordinal == 0) {
                            firstChunk.countDown();
                            try {
                                if (!controlFinished.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Control did not run between hidden chunks");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IocExtractorException("Interrupted publication probe", interrupted);
                            }
                        }
                    }
                });
                assertThat(firstChunk.await(5, TimeUnit.SECONDS)).isTrue();
                fixture.write(OperationClass.CONTROL, c -> {
                    fixture.probe.advanceEpoch(c, "control-only");
                    assertThat(fixture.probe.receipt(c, unit.unitId())).isEmpty(); return null;
                });
                assertThat(fixture.read("row", "masks", "key-0")).isEmpty();
                controlFinished.countDown();
                worker.get(10, TimeUnit.SECONDS);
            } finally {
                controlFinished.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
            fixture.seal(unit);
            fixture.publish(unit, NOW, "bulk-receipt");
            try (Connection connection = fixture.dataSource.getConnection();
                 var query = connection.prepareStatement("EXPLAIN QUERY PLAN " + SqlitePublicationExperiment.LOOKUP_SQL)) {
                query.setString(1, "row"); query.setString(2, "masks"); query.setString(3, "key-4000");
                List<String> plan = new ArrayList<>();
                try (var result = query.executeQuery()) {
                    while (result.next()) { plan.add(result.getString("detail")); }
                }
                assertThat(plan).anyMatch(detail -> detail.contains("SEARCH f USING INDEX experiment_fact_lookup"));
                assertThat(plan).noneMatch(detail -> detail.startsWith("SCAN f"));
                assertThat(plan).noneMatch(detail -> detail.contains("USE TEMP B-TREE FOR ORDER BY"));
                assertThat(fixture.probe.read(connection, "row", "masks", "key-4000")).contains("value");
                assertThat(connection.getMetaData().getDriverVersion()).isEqualTo("3.53.2.0");
                try (var version = connection.createStatement(); var result = version.executeQuery("PRAGMA user_version")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
            }
            assertThat(fixture.admission.snapshot().get(OperationClass.CONTROL).completed()).isEqualTo(1);
            assertThat(fixture.admission.snapshot().get(OperationClass.PROMOTION).completed()).isEqualTo(19);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HikariDataSource dataSource;
        private final JdbcWriterAdmission admission = new JdbcWriterAdmission();
        private final SqlitePublicationExperiment probe = new SqlitePublicationExperiment();

        Fixture(Path directory) throws SQLException { this(directory, true); }

        Fixture(Path directory, boolean initialize) throws SQLException {
            dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                    new SqliteDataSourceSettings("publication-experiment", "jdbc:sqlite:"
                            + directory.resolve("publication.db"), "low-memory", 4, 4));
            try (Connection connection = dataSource.getConnection()) {
                if (initialize) { probe.initialize(connection); }
            } catch (SQLException failure) {
                dataSource.close();
                throw failure;
            }
        }

        Attempt begin(String identity, int chunks, int facts, long deadline, Map<String, Long> dependencies) {
            return write(OperationClass.PROMOTION,
                    c -> probe.begin(c, identity, PINS, new Manifest(chunks, facts, deadline, dependencies)));
        }

        void append(Attempt attempt, int ordinal, List<Fact> facts) {
            write(OperationClass.PROMOTION, c -> { probe.append(c, attempt, ordinal, facts); return null; });
        }

        void seal(Attempt attempt) {
            write(OperationClass.PROMOTION, c -> { probe.seal(c, attempt); return null; });
        }

        SqlitePublicationExperiment.Receipt publish(Attempt attempt, long time, String value) {
            return write(OperationClass.PROMOTION, c -> probe.publish(c, attempt, time, value));
        }

        java.util.Optional<SqlitePublicationExperiment.Receipt> receipt(Attempt attempt) throws SQLException {
            try (Connection connection = dataSource.getConnection()) { return probe.receipt(connection, attempt.unitId()); }
        }

        java.util.Optional<String> read(String family, String scope, String key) throws SQLException {
            try (Connection connection = dataSource.getConnection()) { return probe.read(connection, family, scope, key); }
        }

        <T> T write(OperationClass operation, SqlWork<T> work) {
            return admission.execute(operation, () -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        T result = work.apply(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException | Error failure) {
                        try { connection.rollback(); }
                        catch (SQLException rollback) { failure.addSuppressed(rollback); }
                        throw failure;
                    }
                } catch (SQLException failure) {
                    throw new IocExtractorException("Publication experiment transaction failed", failure);
                }
            });
        }

        @Override public void close() { dataSource.close(); }
    }

    @FunctionalInterface
    private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
}
