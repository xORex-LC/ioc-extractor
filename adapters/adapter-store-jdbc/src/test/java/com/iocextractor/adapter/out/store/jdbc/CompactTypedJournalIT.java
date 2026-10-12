package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compact evidence changes physical cost, never typed visibility, receipt authority or transfer completeness. */
@IntegrationTest
@Timeout(45)
class CompactTypedJournalIT {
    @TempDir Path root;

    @ParameterizedTest
    @EnumSource(JdbcTypedJournal.class)
    void atomicChunkRetainsNullBlobTombstoneDeadlinesAndExactReplay(JdbcTypedJournal journal) throws Exception {
        try (var fixture = new Transport(root)) {
            var chunk = fixture.chunk(List.of(1, new byte[]{0, -1}, 1000L), false, true);
            assertThat(chunk.add(fixture.effect(Arrays.asList(2, null, null), true), true)).isTrue();
            assertThat(chunk.add(fixture.effect(List.of(3, "literal", 2000L), false), false)).isTrue();
            var attempt = fixture.begin(1, 3);

            fixture.append(attempt, 0, chunk, journal);
            fixture.append(attempt, 0, chunk, journal);

            assertThat(fixture.number("SELECT COUNT(*) FROM publication_fact")).isEqualTo(journal == JdbcTypedJournal.CHUNK_POINTER ? 1 : 3);
            assertThat(fixture.number("SELECT contributions FROM publication_unit")).isEqualTo(3);
            assertThat(fixture.number("SELECT COUNT(*) FROM typed_data")).isZero();
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_version_typed_data")).isEqualTo(3);
            assertThat(fixture.number("SELECT deadline_anchor FROM publication_version_typed_data WHERE id=1")).isEqualTo(attempt.unitId());
            assertThat(fixture.number("SELECT absolute_deadline FROM publication_version_typed_data WHERE id=3")).isEqualTo(2000);
            fixture.write(c -> {
                fixture.protocol.seal(c, attempt);
                fixture.protocol.publish(c, attempt, 500, "receipt", new FixedRecordValidityPolicy(Duration.ofHours(1)));
            });
            assertThat(fixture.number("SELECT COUNT(*) FROM typed_data")).isEqualTo(2);
            assertThat(fixture.number("SELECT _valid_until_epoch_ms FROM typed_data WHERE id=1")).isEqualTo(3_600_500);
            try (var c = fixture.data.getConnection(); var s = c.createStatement(); var values = s.executeQuery("SELECT value FROM typed_data WHERE id=1")) {
                assertThat(values.next()).isTrue(); assertThat(values.getBytes(1)).containsExactly((byte) 0, (byte) -1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"value", "null", "order", "tombstone", "time", "scope", "stamp"})
    void changedChunkRetryNeverChangesAcknowledgedTypedRows(String change) throws Exception {
        try (var fixture = new Transport(root)) {
            var first = fixture.effect(List.of(1, "original", 1000L), false);
            var second = fixture.effect(List.of(2, "second", 2000L), false);
            var chunk = new JdbcTypedPublicationChunk(Set.of("typed_data"));
            assertThat(chunk.add(first, false)).isTrue(); assertThat(chunk.add(second, false)).isTrue();
            var attempt = fixture.begin(1, 2);
            fixture.append(attempt, 0, chunk, JdbcTypedJournal.CHUNK_POINTER);
            var retry = new JdbcTypedPublicationChunk(Set.of("typed_data"));
            switch (change) {
                case "value" -> first = fixture.effect(List.of(1, "changed", 1000L), false);
                case "null" -> first = fixture.effect(Arrays.asList(1, null, 1000L), false);
                case "tombstone" -> first = fixture.effect(first.values(), true);
                default -> { }
            }
            assertThat(retry.add(change.equals("order") ? second : first, change.equals("time"))).isTrue();
            assertThat(retry.add(change.equals("order") ? first : second, false)).isTrue();
            var retryAttempt = change.equals("stamp")
                    ? new JdbcPublicationProtocol.Attempt(attempt.unitId(), attempt.fence(), attempt.stamp() + 1) : attempt;

            assertThatThrownBy(() -> fixture.write(c -> retry.append(c, fixture.protocol, retryAttempt,
                    change.equals("scope") ? "other" : "payload", 0, JdbcTypedJournal.CHUNK_POINTER))).isInstanceOf(SQLException.class);

            assertThat(fixture.number("SELECT COUNT(*) FROM publication_version_typed_data")).isEqualTo(2);
            assertThat(fixture.number("SELECT contributions FROM publication_unit")).isEqualTo(2);
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_chunk")).isOne();
        }
    }

    @Test
    void manifestRejectsMissingExtraOutOfOrderAndObsoleteChunks() throws Exception {
        try (var fixture = new Transport(root)) {
            var first = fixture.chunk(List.of(1, "first", 1000L), false, false);
            var second = fixture.chunk(List.of(2, "second", 1000L), false, false);
            var attempt = fixture.begin(2, 2);
            assertThatThrownBy(() -> fixture.append(attempt, 1, second, JdbcTypedJournal.CHUNK_POINTER)).isInstanceOf(SQLException.class);
            fixture.append(attempt, 0, first, JdbcTypedJournal.CHUNK_POINTER);
            assertThatThrownBy(() -> fixture.write(c -> fixture.protocol.seal(c, attempt))).isInstanceOf(SQLException.class);
            fixture.append(attempt, 1, second, JdbcTypedJournal.CHUNK_POINTER);
            assertThatThrownBy(() -> fixture.append(attempt, 2, second, JdbcTypedJournal.CHUNK_POINTER)).isInstanceOf(SQLException.class);
            fixture.write(c -> fixture.protocol.rebuild(c, attempt, "pins",
                    new JdbcPublicationProtocol.Manifest(1, 1, Long.MAX_VALUE, Map.of("payload", 0L))));
            assertThatThrownBy(() -> fixture.append(attempt, 0, first, JdbcTypedJournal.CHUNK_POINTER)).isInstanceOf(SQLException.class);
            assertThat(fixture.number("SELECT COUNT(*) FROM typed_data")).isZero();
        }
    }

    @Test
    void typedInsertFailureRollsBackCompactEvidenceAndAllowsCorrectedRetry() throws Exception {
        try (var fixture = new Transport(root)) {
            var broken = fixture.chunk(List.of(1, "first", 1000L), false, false);
            assertThat(broken.add(fixture.effect(List.of(1, "duplicate-key", 1000L), false), false)).isTrue();
            var attempt = fixture.begin(1, 2);
            assertThatThrownBy(() -> fixture.append(attempt, 0, broken, JdbcTypedJournal.CHUNK_POINTER)).isInstanceOf(SQLException.class);
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_fact")).isZero();
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_chunk")).isZero();
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_version_typed_data")).isZero();
            assertThat(fixture.number("SELECT contributions FROM publication_unit")).isZero();
            var corrected = fixture.chunk(List.of(1, "first", 1000L), false, false);
            assertThat(corrected.add(fixture.effect(List.of(2, "second", 1000L), false), false)).isTrue();
            fixture.append(attempt, 0, corrected, JdbcTypedJournal.CHUNK_POINTER);
            fixture.write(c -> { fixture.protocol.seal(c, attempt); fixture.protocol.publish(c, attempt, 500, "receipt"); });
            assertThat(fixture.number("SELECT COUNT(*) FROM typed_data")).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1_048_577})
    void transportRefusesByteBudgetsOutsideTheBoundedPublicationContract(int bytes) {
        assertThatThrownBy(() -> new JdbcTypedPublicationChunk(Set.of("typed_data"), bytes))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid chunk byte limit");
    }

    @Test
    void changedDependencyStillRejectsCompactPublication() throws Exception {
        try (var fixture = new Transport(root)) {
            var attempt = fixture.begin(1, 1);
            fixture.append(attempt, 0, fixture.chunk(List.of(1, "first", 1000L), false, false), JdbcTypedJournal.CHUNK_POINTER);
            fixture.write(c -> { fixture.protocol.seal(c, attempt); fixture.protocol.advanceEpoch(c, "payload"); });
            assertThatThrownBy(() -> fixture.write(c -> fixture.protocol.publish(c, attempt, 500, "receipt")))
                    .isInstanceOf(JdbcPublicationConflict.class);
            assertThat(fixture.number("SELECT COUNT(*) FROM typed_data")).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 257})
    void invalidDeclaredEffectCountCannotAdvanceCompactManifest(int count) throws Exception {
        try (var fixture = new Transport(root)) {
            var attempt = fixture.begin(1, 1);
            assertThatThrownBy(() -> fixture.write(c -> fixture.protocol.appendTypedChunk(c, attempt, 0,
                    "payload", "checksum", count, false))).isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.number("SELECT contributions FROM publication_unit")).isZero();
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_chunk")).isZero();
        }
    }

    @Test
    void formatReadResourceFailureClosesBothOwnedResourcesAndPreservesPrimaryCause() throws Exception {
        int[] closed = {0, 0};
        var rows = stub(java.sql.ResultSet.class, (proxy, method, arguments) -> {
            if (method.getName().equals("next")) { return true; }
            if (method.getName().equals("close")) { closed[0]++; throw new SQLException("metadata rows close"); }
            throw new UnsupportedOperationException(method.getName());
        });
        var statement = stub(java.sql.Statement.class, (proxy, method, arguments) -> {
            if (method.getName().equals("executeQuery")) { return rows; }
            if (method.getName().equals("close")) { closed[1]++; throw new SQLException("metadata statement close"); }
            throw new UnsupportedOperationException(method.getName());
        });
        var connection = stub(Connection.class, (proxy, method, arguments) -> {
            if (method.getName().equals("createStatement")) { return statement; }
            throw new UnsupportedOperationException(method.getName());
        });

        assertThatThrownBy(() -> JdbcTypedJournal.load(connection)).isInstanceOf(SQLException.class)
                .hasMessage("metadata rows close").satisfies(failure ->
                        assertThat(failure.getSuppressed()).singleElement().satisfies(suppressed ->
                                assertThat(suppressed).hasMessage("metadata statement close")));

        assertThat(closed).containsExactly(1, 1);
    }

    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler calls) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, calls));
    }

    @ParameterizedTest
    @CsvSource({"HIDDEN_CHUNK,0", "SEALED,0", "PUBLICATION_STAGED,0", "COMMITTED,0"})
    void processLossRecoversCompleteImportAndReceiptWithoutDuplicateEffects(String phase, int ordinal) throws Exception {
        StagedPublicationFaultIT.killAt(root, phase, ordinal, "import", JdbcTypedJournal.CHUNK_POINTER);
        try (var fixture = new StagedPublicationFixture(root, false, JdbcPublicationObserver.NOOP)) {
            assertThat(fixture.backend.store().journal()).isEqualTo(JdbcTypedJournal.CHUNK_POINTER);
            boolean committed = phase.equals("COMMITTED");
            assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isEqualTo(committed ? StagedPublicationFixture.ROWS + 1 : 1);
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isEqualTo(committed ? 1 : 0);
            if (committed) {
                Files.delete(new ImportWorkspaceLayout(root.resolve("stage")).paths(StagedPublicationFixture.DELIVERY).sealed());
            }
            long before = fixture.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'");
            fixture.promoteHead(); fixture.promoteHead();
            assertThat(fixture.ledger.find(StagedPublicationFixture.DELIVERY).orElseThrow().state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
            assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isEqualTo(StagedPublicationFixture.ROWS + 1);
            assertThat(fixture.number("SELECT COUNT(*) FROM hashes")).isEqualTo(StagedPublicationFixture.ROWS);
            assertThat(fixture.number("SELECT COUNT(*) FROM import_commit")).isOne();
            assertThat(fixture.number("SELECT (SELECT COUNT(*) FROM masks_sources)+(SELECT COUNT(*) FROM hashes_sources)"))
                    .isEqualTo(2 * StagedPublicationFixture.ROWS + 1);
            if (committed) { assertThat(fixture.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'")).isEqualTo(before); }
            var facts = fixture.facts("import_commit");
            for (int page = 0; page < 30; page++) { fixture.backend.cleanup().purgeJournal(128); }
            assertThat(fixture.facts("import_commit")).isEqualTo(facts);
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_fact")).isZero();
            assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isEqualTo(StagedPublicationFixture.ROWS + 1);
        }
    }

    @Test
    void persistedFormatAndPinsRejectUnannouncedPhysicalModeChange() throws Exception {
        try (var fixture = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, JdbcTypedJournal.CHUNK_POINTER)) {
            fixture.seed();
            assertThat(JdbcStagedCanonicalBackend.reopen(fixture.backend.configuration(), JdbcPublicationObserver.NOOP).store().journal())
                    .isEqualTo(JdbcTypedJournal.CHUNK_POINTER);
            try (var c = fixture.dataframe.getConnection(); var s = c.createStatement()) {
                s.executeUpdate("UPDATE publication_journal_format SET format='ROW_POINTER'");
            }
            assertThatThrownBy(() -> JdbcStagedCanonicalBackend.reopen(fixture.backend.configuration(), JdbcPublicationObserver.NOOP))
                    .hasRootCauseMessage("Staged canonical configuration changed; drained migration required");
        }
    }

    @ParameterizedTest
    @EnumSource(JdbcReceiptPayloadLayout.class)
    void directAckBetweenHiddenTransferAndPublicationForcesRebuildWithoutLostGeneration(JdbcReceiptPayloadLayout layout) throws Exception {
        var current = new java.util.concurrent.atomic.AtomicReference<StagedPublicationFixture>();
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var rebuilds = new java.util.concurrent.atomic.AtomicInteger();
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
            if (phase == JdbcPublicationObserver.Phase.BEFORE_PUBLICATION && armed.getAndSet(false)) {
                var projections = current.get().backend.directProjections();
                assertThat(projections.acknowledge(new com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement(
                        "masks", projections.load("masks").requiredGeneration()))).isTrue();
            }
        }, JdbcTypedJournal.CHUNK_POINTER, layout)) {
            current.set(fixture); fixture.seed(); armed.set(true);

            fixture.backend.confirmations().confirm(fixture.document("next", "next.example", "next-feed"));

            assertThat(rebuilds).hasValue(1);
            var projection = fixture.backend.directProjections().load("masks");
            assertThat(projection.projectedGeneration().value()).isEqualTo(1);
            assertThat(projection.requiredGeneration().value()).isEqualTo(2);
            assertThat(fixture.number("SELECT COUNT(*) FROM masks WHERE mask='next.example'")).isOne();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown"})
    void malformedPersistedFormatFailsClosedWhileAbsentOldDiscriminatorRemainsCompatible(String defect) throws Exception {
        try (var fixture = new Transport(root); var c = fixture.data.getConnection(); var s = c.createStatement()) {
            assertThat(JdbcTypedJournal.load(c)).isEqualTo(JdbcTypedJournal.ROW_POINTER);
            JdbcTypedJournal.CHUNK_POINTER.persist(c);
            s.executeUpdate(defect.equals("missing") ? "DELETE FROM publication_journal_format"
                    : "UPDATE publication_journal_format SET format='unknown'");
            assertThatThrownBy(() -> JdbcTypedJournal.load(c)).isInstanceOf(SQLException.class);
        }
    }

    private static final class Transport implements AutoCloseable {
        final HikariDataSource data;
        final JdbcPublicationProtocol protocol = new JdbcPublicationProtocol();
        final JdbcPublicationTable table;
        Transport(Path root) throws Exception {
            data = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                    new SqliteDataSourceSettings("compact-test", "jdbc:sqlite:" + root.resolve("transport.db"), "low-memory", 2, 2));
            try (var c = data.getConnection()) {
                protocol.initialize(c);
                JdbcPublicationTable.execute(c, "CREATE TABLE typed_data(id INTEGER PRIMARY KEY,value BLOB,_valid_until_epoch_ms INTEGER)");
                table = JdbcPublicationTable.inspect(c, "typed_data");
                JdbcTypedVersionTable.create(c, table, true);
                JdbcPublicationTable.execute(c, "DROP TABLE typed_data");
                JdbcTypedVersionTable.createView(c, table, true);
            } catch (Exception | Error failure) { data.close(); throw failure; }
        }
        JdbcPrivateChanges.Effect effect(List<Object> values, boolean deleted) {
            return new JdbcPrivateChanges.Effect(table, values, null, deleted, false);
        }
        JdbcTypedPublicationChunk chunk(List<Object> values, boolean deleted, boolean publicationTime) throws SQLException {
            var chunk = new JdbcTypedPublicationChunk(Set.of("typed_data"));
            assertThat(chunk.add(effect(values, deleted), publicationTime)).isTrue(); return chunk;
        }
        JdbcPublicationProtocol.Attempt begin(int chunks, int rows) throws SQLException {
            var attempt = new JdbcPublicationProtocol.Attempt[1];
            write(c -> attempt[0] = protocol.begin(c, "unit", "pins", new JdbcPublicationProtocol.Manifest(chunks, rows, Long.MAX_VALUE, Map.of("payload", 0L))));
            return attempt[0];
        }
        void append(JdbcPublicationProtocol.Attempt attempt, int ordinal, JdbcTypedPublicationChunk chunk, JdbcTypedJournal journal) throws SQLException {
            write(c -> chunk.append(c, protocol, attempt, "payload", ordinal, journal));
        }
        void write(Work work) throws SQLException {
            try (var c = data.getConnection()) {
                c.setAutoCommit(false);
                try { work.apply(c); c.commit(); }
                catch (SQLException | RuntimeException | Error failure) { JdbcLifecycleTransactions.rollback(c, failure); throw failure; }
            }
        }
        long number(String sql) throws SQLException {
            try (var c = data.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
                assertThat(r.next()).isTrue(); return r.getLong(1);
            }
        }
        @Override public void close() { data.close(); }
    }
    @FunctionalInterface private interface Work { void apply(Connection connection) throws SQLException; }
}
