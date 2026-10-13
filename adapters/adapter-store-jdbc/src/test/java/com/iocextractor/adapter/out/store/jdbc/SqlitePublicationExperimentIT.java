package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.adapter.out.store.jdbc.JdbcPublicationProtocol.Attempt;
import com.iocextractor.adapter.out.store.jdbc.JdbcPublicationProtocol.Fact;
import com.iocextractor.adapter.out.store.jdbc.JdbcPublicationProtocol.Manifest;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.CanonicalMatchCardinality;
import com.iocextractor.application.artifact.CanonicalRecordMutationKind;
import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission.OperationClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Physical publication qualification only; does not implement or replace canonical ports. */
@IntegrationTest
@Timeout(30)
class SqlitePublicationExperimentIT {
    private static final long NOW = 1_800_000_000_000L;
    private static final String PINS = "schema:12/catalog:fixture/policy:fixture/digest:fixture";
    @TempDir Path directory;

    @Test
    void emptyCapturePagesSignedNativeIdsIncludingLongMinimumWithoutSkippingTheFirstPage() throws Exception {
        try (var fixture = new Fixture(directory); var c = fixture.dataSource.getConnection()) {
            JdbcPublicationTable.execute(c, "CREATE TABLE signed_capture(id INTEGER PRIMARY KEY,value TEXT)");
            var table = JdbcPublicationTable.inspect(c, "signed_capture");
            JdbcPrivateChangeCapture.install(c, List.of(table));
            c.setAutoCommit(false);
            try (var insert = c.prepareStatement("INSERT INTO signed_capture VALUES(?,?)")) {
                for (int ordinal = 0; ordinal < 257; ordinal++) {
                    insert.setLong(1, Long.MIN_VALUE + ordinal); insert.setString(2, "value-" + ordinal); insert.addBatch();
                }
                insert.executeBatch();
            }
            var values = new ArrayList<String>();
            var first = JdbcPrivateChanges.read(c, table, JdbcPrivateChanges.Cursor.START, effect -> values.add((String) effect.value("value")));
            assertThat(values).hasSize(256).startsWith("value-0").endsWith("value-255");
            var tail = JdbcPrivateChanges.read(c, table, first, effect -> values.add((String) effect.value("value")));
            assertThat(values).hasSize(257).endsWith("value-256");
            assertThat(JdbcPrivateChanges.read(c, table, tail, effect -> { throw new AssertionError("no remaining address"); })).isEqualTo(tail);
            c.rollback();
        }
    }

    @ParameterizedTest
    @CsvSource({"id,WITHOUT ROWID", "rowid,''"})
    void captureFallsBackToTypedKeysWhenNativeRowReferencesAreUnavailable(String key, String suffix) throws Exception {
        try (var fixture = new Fixture(directory); var c = fixture.dataSource.getConnection()) {
            JdbcPublicationTable.execute(c, "CREATE TABLE fallback_capture(" + key + " INTEGER PRIMARY KEY,value BLOB) " + suffix);
            var table = JdbcPublicationTable.inspect(c, "fallback_capture");
            JdbcPrivateChangeCapture.install(c, List.of(table));
            c.setAutoCommit(false);
            try (var insert = c.prepareStatement("INSERT INTO fallback_capture VALUES(?,?)")) {
                insert.setInt(1, -1); insert.setBytes(2, new byte[]{0, -1, 2}); assertThat(insert.executeUpdate()).isOne();
            }
            var effects = new ArrayList<JdbcPrivateChanges.Effect>();
            JdbcPrivateChanges.read(c, table, JdbcPrivateChanges.Cursor.START, effects::add);
            assertThat(effects).singleElement().satisfies(effect -> {
                assertThat(effect.value(key)).isEqualTo(-1);
                assertThat((byte[]) effect.value("value")).containsExactly((byte) 0, (byte) -1, (byte) 2);
                assertThat(effect.previous()).isNull();
            });
            preparedUpdate(c, "DELETE FROM fallback_capture");
            effects.clear(); JdbcPrivateChanges.read(c, table, JdbcPrivateChanges.Cursor.START, effects::add);
            assertThat(effects).isEmpty(); c.rollback();
        }
    }

    @Test
    void emptyCaptureStreamsFinalValuesAcrossNegativeIdsAndReinsertion() throws Exception {
        try (var fixture = new Fixture(directory); var c = fixture.dataSource.getConnection()) {
            JdbcPublicationTable.execute(c, "CREATE TABLE compact_capture(id INTEGER PRIMARY KEY,value TEXT)");
            var table = JdbcPublicationTable.inspect(c, "compact_capture");
            JdbcPrivateChangeCapture.install(c, List.of(table));
            c.setAutoCommit(false);
            preparedUpdate(c, "INSERT INTO compact_capture VALUES(-2,'initial'),(3,'transient')");
            preparedUpdate(c, "UPDATE compact_capture SET id=-1,value='final' WHERE id=-2");
            preparedUpdate(c, "DELETE FROM compact_capture WHERE id=3");
            preparedUpdate(c, "INSERT INTO compact_capture VALUES(3,NULL)");
            var effects = new ArrayList<JdbcPrivateChanges.Effect>();
            var last = JdbcPrivateChanges.read(c, table, JdbcPrivateChanges.Cursor.START, effects::add);
            assertThat(effects).hasSize(2).allSatisfy(effect -> {
                assertThat(effect.previous()).isNull(); assertThat(effect.deleted()).isFalse();
            });
            assertThat(effects).extracting(effect -> effect.value("value")).containsExactly("final", null);
            assertThat(queryNumber(c, "SELECT COUNT(*) FROM publication_before_compact_capture")).isZero();
            assertThat(queryNumber(c, "SELECT COUNT(*) FROM pragma_table_info('publication_delta_compact_capture')")).isEqualTo(1);
            assertThat(JdbcPrivateChanges.read(c, table, last, effects::add)).isEqualTo(last);
            c.rollback();
        }
    }

    @Test
    void committedSlotCompletenessUsesLifecycleLookupAndRejectsMissingAssignments() throws Exception {
        try (var fixture = new StagedPublicationFixture(directory, true, JdbcPublicationObserver.NOOP)) {
            fixture.seed(); assertThat(fixture.snapshot()).hasSize(1);
            var captured = new java.util.concurrent.atomic.AtomicReference<String>();
            try (var connection = fixture.dataframe.getConnection()) {
                var traced = proxy(Connection.class, connection, (method, arguments) -> {
                    if (method.getName().equals("prepareStatement") && ((String) arguments[0]).contains("a.lifecycle_id")) {
                        captured.set((String) arguments[0]);
                    }
                    return invoke(connection, method, arguments);
                });
                var registry = new JdbcExportSlotRegistry();
                registry.requireCurrentSnapshot(traced, "fault-profile", "masks", StagedPublicationFixture.NOW);
                assertThat(captured.get()).isNotNull();
                var plan = new ArrayList<String>();
                try (var explain = connection.prepareStatement("EXPLAIN QUERY PLAN " + captured.get())) {
                    explain.setLong(1, StagedPublicationFixture.NOW.toEpochMilli());
                    explain.setString(2, "fault-profile"); explain.setString(3, "masks");
                    try (var rows = explain.executeQuery()) { while (rows.next()) { plan.add(rows.getString("detail")); } }
                }
                assertThat(plan).noneMatch(step -> step.contains("MATERIALIZE export_slot_assignment") || step.contains("SCAN a"))
                        .anyMatch(step -> step.contains("profile=? AND artifact=? AND lifecycle_id=?"));

                JdbcPublicationTable.execute(connection, "DELETE FROM publication_version_export_slot_assignment");
                assertThatThrownBy(() -> registry.requireCurrentSnapshot(connection, "fault-profile", "masks", StagedPublicationFixture.NOW))
                        .isInstanceOf(SQLException.class).hasMessage("Export-slot assignments are incomplete for fault-profile/masks");
                // Expired membership does not require a slot in the active snapshot.
                registry.requireCurrentSnapshot(connection, "fault-profile", "masks", StagedPublicationFixture.NOW.plusSeconds(43200));
            }
        }
    }

    @Test
    void typedChunkFramingPreservesSqlKindsNullAndFieldBoundariesAndRejectsOversizedPayloads() throws Exception {
        var fingerprints = new java.util.HashSet<String>();
        Object[] values = {null, "", "1", (byte) 1, (short) 2, 3, 4L, 1.0f, 2.0d, new byte[0], true, false};
        for (Object value : values) {
            var digest = new JdbcPublicationChunkDigest();
            digest.row(); digest.field(value);
            assertThat(fingerprints.add(digest.finish())).as("distinct SQL value %s", value).isTrue();
        }
        var combined = new JdbcPublicationChunkDigest();
        combined.field("ab"); combined.field("c");
        var split = new JdbcPublicationChunkDigest();
        split.field("a"); split.field("bc");
        assertThat(combined.finish()).isNotEqualTo(split.finish());
        assertThatThrownBy(() -> new JdbcPublicationChunkDigest().field(new Object()))
                .isInstanceOf(SQLException.class).hasMessageContaining("Unsupported typed");
        assertThatThrownBy(() -> new JdbcPublicationChunkDigest().field(new byte[1024 * 1024 + 1]))
                .isInstanceOf(SQLException.class).hasMessageContaining("byte bound");
        assertThatThrownBy(() -> new JdbcPublicationChunkDigest().field("x".repeat(1024 * 1024 + 1)))
                .isInstanceOf(SQLException.class).hasMessageContaining("byte bound");
        assertThatThrownBy(() -> {
            var digest = new JdbcPublicationChunkDigest();
            digest.field("x".repeat(600_000)); digest.field("y".repeat(600_000));
        }).isInstanceOf(SQLException.class).hasMessageContaining("byte bound");
        assertThatThrownBy(() -> {
            var digest = new JdbcPublicationChunkDigest();
            for (int index = 0; index < 257; index++) { digest.row(); }
        }).isInstanceOf(SQLException.class).hasMessageContaining("row bound");
    }

    @Test
    void typedCaptureRefusesTablesWithoutStableKeysAndMissingNativeShapes() throws Exception {
        assertThatThrownBy(() -> new JdbcPublicationTable("empty", List.of(), List.of("id"), "unused"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcPublicationTable("keyless", List.of("value"), List.of(), "unused"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcPublicationTable("foreign_key", List.of("value"), List.of("other"), "unused"))
                .isInstanceOf(IllegalArgumentException.class);
        try (var fixture = new Fixture(directory); var connection = fixture.dataSource.getConnection()) {
            assertThatThrownBy(() -> JdbcPublicationTable.inspect(connection, "missing"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Missing native");
            JdbcPublicationTable.execute(connection, "CREATE TABLE keyless(value TEXT)");
            assertThatThrownBy(() -> JdbcPublicationTable.inspect(connection, "keyless"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("primary key");
        }
    }

    @Test
    void selectiveHydrationPreservesWideRowsAcrossMultipleByteBoundedBatches() throws Exception {
        var limits = new DocumentPreparationLimits(4 * 1024 * 1024, 64, 4096, 2048, 16 * 1024 * 1024, 256 * 1024 * 1024, 256);
        try (var source = new Fixture(directory);
             var target = new Fixture(java.nio.file.Files.createDirectory(directory.resolve("wide-hydration")));
             var base = source.dataSource.getConnection(); var working = target.dataSource.getConnection()) {
            String ddl = "CREATE TABLE wide_inputs(id INTEGER PRIMARY KEY,value TEXT,payload BLOB)";
            JdbcPublicationTable.execute(base, ddl); JdbcPublicationTable.execute(working, ddl);
            String text = "Ω".repeat(500);
            byte[] payload = new byte[1024]; java.util.Arrays.fill(payload, (byte) -1);
            try (var insert = base.prepareStatement("INSERT INTO wide_inputs VALUES(?,?,?)")) {
                for (int index = 0; index < 140; index++) {
                    insert.setInt(1, index); insert.setString(2, text); insert.setBytes(3, payload); insert.addBatch();
                }
                insert.executeBatch();
            }
            base.setAutoCommit(false); working.setAutoCommit(false);
            var checkpoints = new java.util.concurrent.atomic.AtomicInteger();
            var hydrator = new JdbcPublicationHydrator(base, working, List.of(), checkpoints::incrementAndGet, limits);

            assertThat(hydrator.copy("wide_inputs", "id>=?", List.of(0))).isEqualTo(140);

            assertThat(checkpoints.get()).isGreaterThan(1);
            try (var query = working.prepareStatement("SELECT id,value,payload FROM wide_inputs ORDER BY id");
                 var rows = query.executeQuery()) {
                for (int index = 0; index < 140; index++) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(index);
                    assertThat(rows.getString(2)).isEqualTo(text); assertThat(rows.getBytes(3)).containsExactly(payload);
                }
                assertThat(rows.next()).isFalse();
            }
            working.rollback(); base.rollback();
            assertThat(queryNumber(working, "SELECT COUNT(*) FROM wide_inputs")).isZero(); working.rollback();
        }
    }

    @Test
    void selectiveHydrationStreamsBoundValuesAndStopsOnInterruptionOrQuotaBeforeCommit() throws Exception {
        var limits = new DocumentPreparationLimits(4 * 1024 * 1024, 64, 4096, 2048, 16 * 1024 * 1024, 256 * 1024 * 1024, 256);
        try (var source = new Fixture(directory);
             var target = new Fixture(java.nio.file.Files.createDirectory(directory.resolve("hydration-target")));
             var base = source.dataSource.getConnection(); var working = target.dataSource.getConnection()) {
            String ddl = "CREATE TABLE bounded_inputs(id INTEGER PRIMARY KEY,value TEXT,payload BLOB)";
            JdbcPublicationTable.execute(base, ddl); JdbcPublicationTable.execute(working, ddl);
            String input = "feed'; DROP TABLE bounded_inputs; -- Ω";
            try (var insert = base.prepareStatement("INSERT INTO bounded_inputs VALUES(?,?,?)")) {
                for (int index = 0; index < 257; index++) {
                    insert.setInt(1, index); insert.setString(2, input); insert.setBytes(3, new byte[]{0, 1, -1}); insert.addBatch();
                }
                insert.executeBatch();
            }
            var quotas = new java.util.concurrent.atomic.AtomicInteger();
            var hydrator = new JdbcPublicationHydrator(base, working, List.of(), quotas::incrementAndGet, limits);
            assertThatThrownBy(() -> hydrator.copy("bounded_inputs", "value=?", List.of(input)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("owned source");
            base.setAutoCommit(false);
            assertThatThrownBy(() -> hydrator.copy("bounded_inputs", "value=?", List.of(input)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("owned source");
            working.setAutoCommit(false);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> hydrator.copy("bounded_inputs", "value=?", List.of(input)))
                        .isInstanceOf(SQLException.class).hasMessageContaining("interrupted");
            } finally { Thread.interrupted(); }
            assertThat(queryNumber(working, "SELECT COUNT(*) FROM bounded_inputs")).isZero();
            assertThat(hydrator.copy("bounded_inputs", "value=?", List.of(input))).isEqualTo(257);
            assertThat(quotas).hasValue(2);
            assertThat(queryNumber(working, "SELECT COUNT(*) FROM bounded_inputs WHERE hex(payload)='0001FF'")).isEqualTo(257);
            working.rollback();
            var failing = new JdbcPublicationHydrator(base, working, List.of(), () -> { throw new IocExtractorException("quota exhausted"); }, limits);
            assertThatThrownBy(() -> failing.copy("bounded_inputs", "value=?", List.of(input)))
                    .isInstanceOf(IocExtractorException.class).hasMessage("quota exhausted");
            working.rollback(); base.rollback();
            assertThat(queryNumber(working, "SELECT COUNT(*) FROM bounded_inputs")).isZero();
            assertThat(queryNumber(base, "SELECT COUNT(*) FROM bounded_inputs")).isEqualTo(257);
            working.rollback(); base.rollback();
        }
    }

    @Test
    void typedChunkStatementReusePreservesMixedFamiliesNullBlobTombstonesAndDeadlineAnchors() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = typedCostTable(fixture, "typed_first");
            var second = typedCostTable(fixture, "typed_second");
            var chunk = new JdbcTypedPublicationChunk(java.util.Set.of(first.name(), second.name()));
            assertThat(chunk.add(new JdbcPrivateChanges.Effect(first,
                    List.of(1, new byte[]{0, -1}, NOW + 1000), null, false, false), true)).isTrue();
            assertThat(chunk.add(new JdbcPrivateChanges.Effect(first,
                    java.util.Arrays.asList(2, null, null), null, true, false), true)).isTrue();
            assertThat(chunk.add(new JdbcPrivateChanges.Effect(second,
                    List.of(3, new byte[]{1, 2}, NOW + 2000), null, false, false), false)).isTrue();
            assertThat(chunk.add(new JdbcPrivateChanges.Effect(first,
                    List.of(4, new byte[]{3}, NOW + 3000), null, false, false), false)).isTrue();
            var attempt = fixture.begin("typed-bindings", 1, 4, Long.MAX_VALUE, Map.of("payload", 0L));
            var prepared = new ArrayList<java.sql.PreparedStatement>();
            fixture.write(OperationClass.PROMOTION, connection -> {
                var traced = proxy(Connection.class, connection, (method, arguments) -> {
                    Object value = invoke(connection, method, arguments);
                    if (method.getName().equals("prepareStatement") && ((String) arguments[0]).startsWith("INSERT INTO \"publication_version_")) {
                        prepared.add((java.sql.PreparedStatement) value);
                    }
                    return value;
                });
                chunk.append(traced, fixture.probe, attempt, "payload", 0);
                assertThat(prepared).hasSize(2);
                for (var statement : prepared) { assertThat(statement.isClosed()).isTrue(); }
                // Acknowledged replay does not reopen statements or append physical versions.
                chunk.append(traced, fixture.probe, attempt, "payload", 0);
                assertThat(prepared).hasSize(2);
                return null;
            });
            try (var connection = fixture.dataSource.getConnection(); var statement = connection.prepareStatement(
                    "SELECT id,value,deleted,absolute_deadline,deadline_anchor FROM publication_version_typed_first ORDER BY id");
                 var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getBytes(2)).containsExactly((byte) 0, (byte) -1);
                assertThat(rows.getObject(4)).isNull(); assertThat(rows.getLong(5)).isEqualTo(attempt.unitId());
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getObject(2)).isNull(); assertThat(rows.getInt(3)).isOne();
                assertThat(rows.getObject(4)).isNull(); assertThat(rows.getObject(5)).isNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(4);
                assertThat(rows.getBytes(2)).containsExactly((byte) 3);
                assertThat(rows.getLong(4)).isEqualTo(NOW + 3000); assertThat(rows.getObject(5)).isNull();
                assertThat(rows.next()).isFalse();
                assertThat(queryNumber(connection, "SELECT COUNT(*) FROM typed_first")).isZero();
                assertThat(queryNumber(connection, "SELECT absolute_deadline FROM publication_version_typed_second")).isEqualTo(NOW + 2000);
            }
            fixture.seal(attempt); fixture.publishWithValidity(attempt, NOW, "bindings-receipt", Duration.ofHours(1));
            try (var connection = fixture.dataSource.getConnection()) {
                assertThat(queryNumber(connection, "SELECT COUNT(*) FROM typed_first")).isEqualTo(2);
                assertThat(queryNumber(connection, "SELECT COUNT(*) FROM typed_second")).isOne();
            }
        }
    }

    @Test
    void typedChunkFailureClosesStatementsAndRollsBackJournalAndVersionsWithoutHidingTheCause() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var table = typedCostTable(fixture, "typed_failure");
            var chunk = new JdbcTypedPublicationChunk(java.util.Set.of());
            for (int id = 1; id <= 2; id++) {
                assertThat(chunk.add(new JdbcPrivateChanges.Effect(table,
                        java.util.Arrays.asList(id, null, null), null, false, false), false)).isTrue();
            }
            var attempt = fixture.begin("typed-failure", 1, 2, Long.MAX_VALUE, Map.of("payload", 0L));
            var prepared = new ArrayList<java.sql.PreparedStatement>();
            var failure = catchThrowable(() -> fixture.write(OperationClass.PROMOTION, connection -> {
                var traced = proxy(Connection.class, connection, (method, arguments) -> {
                    Object value = invoke(connection, method, arguments);
                    if (!method.getName().equals("prepareStatement") || !((String) arguments[0]).startsWith("INSERT INTO \"publication_version_")) { return value; }
                    var statement = (java.sql.PreparedStatement) value;
                    prepared.add(statement);
                    return proxy(java.sql.PreparedStatement.class, statement, (operation, parameters) -> {
                        Object result = invoke(statement, operation, parameters);
                        // Model a driver reporting a failed batch after physical writes.
                        // The owned transaction must roll back every effect, not just the journal.
                        if (operation.getName().equals("executeBatch")) { throw new SQLException("typed write fixture"); }
                        if (operation.getName().equals("close")) { throw new SQLException("typed close fixture"); }
                        return result;
                    });
                });
                chunk.append(traced, fixture.probe, attempt, "payload", 0);
                return null;
            }));
            assertThat(failure).isInstanceOf(IocExtractorException.class).hasRootCauseMessage("typed write fixture");
            assertThat(failure.getCause().getSuppressed()).singleElement()
                    .satisfies(suppressed -> assertThat(suppressed).hasMessage("typed close fixture"));
            assertThat(prepared).hasSize(1);
            assertThat(prepared.getFirst().isClosed()).isTrue();
            try (var connection = fixture.dataSource.getConnection()) {
                assertThat(queryNumber(connection, "SELECT COUNT(*) FROM publication_version_typed_failure")).isZero();
                assertThat(queryNumber(connection, "SELECT COUNT(*) FROM publication_chunk")).isZero();
                assertThat(queryNumber(connection, "SELECT COUNT(*) FROM publication_fact")).isZero();
            }
        }
    }

    private static JdbcPublicationTable typedCostTable(Fixture fixture, String name) {
        return fixture.write(OperationClass.CONTROL, connection -> {
            JdbcPublicationTable.execute(connection, "CREATE TABLE " + name + "(id INTEGER PRIMARY KEY,value BLOB,_valid_until_epoch_ms INTEGER)");
            var table = JdbcPublicationTable.inspect(connection, name);
            JdbcTypedVersionTable.create(connection, table, true);
            JdbcPublicationTable.execute(connection, "DROP TABLE " + name);
            JdbcTypedVersionTable.createView(connection, table, true);
            return table;
        });
    }

    @Test
    void typedChunksBoundPayloadBeforeRetentionAndRetryWithoutDuplicatingPhysicalVersions() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var table = fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "CREATE TABLE typed_payload(id INTEGER PRIMARY KEY,value TEXT,extra TEXT)");
                var shape = JdbcPublicationTable.inspect(c, "typed_payload");
                JdbcTypedVersionTable.create(c, shape, false);
                JdbcPublicationTable.execute(c, "DROP TABLE typed_payload");
                JdbcTypedVersionTable.createView(c, shape, false);
                return shape;
            });
            var first = new JdbcPrivateChanges.Effect(table, List.of(1, "x".repeat(600_000), "a"), null, false, false);
            var chunk = new JdbcTypedPublicationChunk(java.util.Set.of());
            assertThat(chunk.add(first, false)).isTrue();
            assertThat(chunk.add(new JdbcPrivateChanges.Effect(table, List.of(2, "y".repeat(600_000), "b"), null, false, false), false)).isFalse();
            assertThat(chunk.size()).isOne();
            assertThatThrownBy(() -> new JdbcTypedPublicationChunk(java.util.Set.of()).add(new JdbcPrivateChanges.Effect(
                    table, List.of(3, "x".repeat(600_000), "y".repeat(600_000)), null, false, false), false))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chunk byte limit");
            var attempt = fixture.begin("typed-payload", 1, 1, Long.MAX_VALUE, Map.of("payload", 0L));
            fixture.write(OperationClass.PROMOTION, c -> { chunk.append(c, fixture.probe, attempt, "payload", 0); return null; });
            fixture.write(OperationClass.PROMOTION, c -> { chunk.append(c, fixture.probe, attempt, "payload", 0); return null; });
            try (var c = fixture.dataSource.getConnection()) {
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM publication_version_typed_payload")).isOne();
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM typed_payload")).isZero();
            }
            fixture.seal(attempt); fixture.publish(attempt, NOW, "typed-receipt");
            try (var c = fixture.dataSource.getConnection()) {
                assertThat(queryNumber(c, "SELECT length(value) FROM typed_payload")).isEqualTo(600_000);
            }
        }
    }

    @Test
    void numericHydrationAcceptsExactByteLimitsAndRejectsOneByteExcess() throws Exception {
        try (var source = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("numeric-source.db"));
             var working = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("numeric-working.db"))) {
            for (var connection : List.of(source, working)) {
                JdbcPublicationTable.execute(connection, "CREATE TABLE numbers(a REAL PRIMARY KEY,b REAL)");
                connection.setAutoCommit(false);
            }
            JdbcPublicationTable.execute(source, "INSERT INTO numbers VALUES(0.12345678901234567,0.12345678901234567)"); source.commit();
            int sqlFieldBytes = Math.toIntExact(queryNumber(source, "SELECT 5+length(CAST(a AS BLOB)) FROM numbers"));
            try (var select = source.createStatement(); var result = select.executeQuery("SELECT a FROM numbers")) {
                assertThat(result.next()).isTrue();
                assertThat(5 + result.getObject(1).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        .isEqualTo(sqlFieldBytes);
            }
            var exact = new DocumentPreparationLimits(1024 * 1024, 64, 2 * sqlFieldBytes, sqlFieldBytes, 65536, 65536, 1);
            assertThat(new JdbcPublicationHydrator(source, working, List.of(), () -> { }, exact).copy("numbers", "1=1", List.of())).isOne();
            working.rollback(); source.rollback();
            for (var limits : List.of(new DocumentPreparationLimits(1024 * 1024, 64, 2 * sqlFieldBytes, sqlFieldBytes - 1, 65536, 65536, 1),
                    new DocumentPreparationLimits(1024 * 1024, 64, 2 * sqlFieldBytes - 1, sqlFieldBytes, 65536, 65536, 1))) {
                var hydrator = new JdbcPublicationHydrator(source, working, List.of(), () -> { }, limits);
                assertThatThrownBy(() -> hydrator.copy("numbers", "1=1", List.of()))
                        .hasMessage("Committed native row exceeds canonical preparation limits");
                working.rollback(); source.rollback();
                assertThat(queryNumber(working, "SELECT COUNT(*) FROM numbers")).isZero();
            }
        }
    }

    @Test
    void missingLogicalOwnershipAndIgnoredReleaseCannotAuthorizeAnotherPublication() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var owners = new JdbcPublicationOwner();
            fixture.write(OperationClass.CONTROL, c -> { owners.initialize(c); return null; });
            var owner = fixture.write(OperationClass.CONTROL, c -> owners.acquire(c, "owned-unit", owners.fence(c)));
            fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "CREATE TRIGGER suppress_release BEFORE UPDATE ON publication_logical_owner"
                        + " WHEN NEW.unit IS NULL BEGIN SELECT RAISE(IGNORE); END"); return null;
            });
            assertThatThrownBy(() -> fixture.write(OperationClass.CONTROL, c -> { owners.release(c, owner); return null; }))
                    .hasRootCauseMessage("Logical promotion release failed");
            assertThatThrownBy(() -> fixture.write(OperationClass.CONTROL, c -> owners.acquire(c, "different-unit", owner.fence())))
                    .hasRootCauseMessage("Logical promotion ownership changed or occupied");
            fixture.write(OperationClass.CONTROL, c -> { JdbcPublicationTable.execute(c, "DELETE FROM publication_logical_owner"); return null; });
            assertThatThrownBy(() -> fixture.write(OperationClass.CONTROL, owners::fence))
                    .hasRootCauseMessage("Missing logical publication ownership");
        }
    }

    @Test
    void transactionCleanupPreservesThePrimaryFailureAndReportsUnaccompaniedRestorationFailure() throws Exception {
        try (var physical = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cleanup-failure.db"))) {
            Connection failing = proxy(Connection.class, physical, (method, arguments) -> {
                if (method.getName().equals("rollback") || method.getName().equals("setAutoCommit")) {
                    throw new SQLException("cleanup " + method.getName());
                }
                return invoke(physical, method, arguments);
            });
            var primary = new IllegalStateException("native mutation refused");
            JdbcLifecycleTransactions.rollback(failing, primary);
            JdbcLifecycleTransactions.restoreAutoCommit(failing, true, primary);
            assertThat(primary.getSuppressed()).extracting(Throwable::getMessage)
                    .containsExactly("cleanup rollback", "cleanup setAutoCommit");
            assertThatThrownBy(() -> JdbcLifecycleTransactions.restoreAutoCommit(failing, true, null))
                    .isInstanceOf(SQLException.class).hasMessage("cleanup setAutoCommit");
            assertThat(physical.isClosed()).isFalse();
        }
    }

    @Test
    void slotMembershipOverlayExcludesExpiredAndSelectedBaseRowsAndRetainsOnlyPrivateReplacements() throws Exception {
        try (var base = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("slot-base.db"));
             var working = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("slot-working.db"))) {
            for (var c : List.of(base, working)) {
                JdbcPublicationTable.execute(c, "CREATE TABLE masks(id INTEGER PRIMARY KEY,_lifecycle_id INTEGER,_valid_until_epoch_ms INTEGER)");
                c.setAutoCommit(false);
            }
            try (var insert = base.prepareStatement("INSERT INTO masks VALUES(?,?,?)")) {
                for (int row = 1; row <= 300; row++) {
                    insert.setInt(1, row); insert.setInt(2, row); insert.setLong(3, NOW + 1000); insert.addBatch();
                }
                insert.executeBatch();
            }
            JdbcPublicationTable.execute(base, "INSERT INTO masks VALUES(999,999," + NOW + ")");
            JdbcPublicationTable.execute(base, "CREATE TEMP TABLE publication_selected_row(artifact TEXT,row_id INTEGER)");
            JdbcPublicationTable.execute(base, "INSERT INTO publication_selected_row VALUES('masks',7),('masks',8)");
            base.commit();
            var quotas = new java.util.concurrent.atomic.AtomicInteger();
            var slots = new JdbcPublicationSlotBase(base, working, quotas::incrementAndGet);
            Thread.currentThread().interrupt();
            try { assertThatThrownBy(() -> slots.hydrate("masks", NOW)).hasMessage("Slot membership hydration interrupted"); }
            finally { Thread.interrupted(); }
            working.rollback();
            JdbcPublicationTable.execute(working, "INSERT INTO masks VALUES(7,701," + (NOW + 5000) + "),(400,400," + (NOW + 5000) + ")");
            slots.hydrate("masks", NOW); slots.hydrate("masks", NOW);
            assertThat(quotas).hasValue(2);
            assertThat(slots.minimumDeadline()).isEqualTo(NOW + 1000);
            assertThat(queryNumber(working, "SELECT COUNT(*) FROM publication_slot_view_masks")).isEqualTo(300);
            assertThat(queryNumber(working, "SELECT COUNT(*) FROM publication_slot_view_masks WHERE id IN(8,999)")).isZero();
            assertThat(queryNumber(working, "SELECT _lifecycle_id FROM publication_slot_view_masks WHERE id=7")).isEqualTo(701);
            working.rollback(); base.rollback();
        }
    }

    @Test
    void protocolRejectsUnownedTransactionsOversizedUtf8AndStaleReadOverlays() throws Exception {
        try (var fixture = new Fixture(directory); var connection = fixture.dataSource.getConnection()) {
            var manifest = new Manifest(1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            assertThatThrownBy(() -> fixture.probe.begin(connection, "unowned", PINS, manifest))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Caller-owned");
            assertThatThrownBy(() -> new JdbcPublicationOwner().acquire(connection, "unowned", 0))
                    .isInstanceOf(SQLException.class).hasMessageContaining("owned transaction");
            var attempt = fixture.begin("utf8-bound", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            assertThatThrownBy(() -> fixture.append(attempt, 0, List.of(new Fact("row", "masks", "one", "Ω".repeat(600_000)))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chunk byte limit");
            fixture.append(attempt, 0, List.of(new Fact("row", "masks", "one", "kept")));
            connection.setAutoCommit(false);
            assertThat(fixture.probe.readOwn(connection, connection, attempt, "row", "masks", "one")).contains("kept");
            assertThat(fixture.probe.readOwn(connection, connection, attempt, "row", "masks", "missing")).isEmpty();
            connection.rollback();
            fixture.write(OperationClass.CONTROL, c -> { fixture.probe.cancel(c, attempt); return null; });
            assertThatThrownBy(() -> fixture.probe.readOwn(connection, connection, attempt, "row", "masks", "missing"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Stale attempt");
            connection.rollback();
        }
    }

    @Test
    void borrowedReadPolicyRestorationReportsEveryFailureAndInitializationPreservesItsPrimaryError() throws Exception {
        var limits = new DocumentPreparationLimits(4 * 1024 * 1024, 64, 4096, 2048, 16 * 1024 * 1024, 256 * 1024 * 1024, 256);
        try (var fixture = new Fixture(directory); var physical = fixture.dataSource.getConnection()) {
            var fail = new java.util.concurrent.atomic.AtomicBoolean();
            Connection borrowed = proxy(Connection.class, physical, (method, arguments) -> {
                Object value = invoke(physical, method, arguments);
                if (method.getName().equals("createStatement")) {
                    var statement = (java.sql.Statement) value;
                    return proxy(java.sql.Statement.class, statement, (call, parameters) -> {
                        if (fail.get() && call.getName().equals("execute")) { throw new SQLException("restore " + parameters[0]); }
                        return invoke(statement, call, parameters);
                    });
                }
                return value;
            });
            var policy = new JdbcPublicationReadLimits(borrowed, limits);
            fail.set(true);
            assertThatThrownBy(policy::close).isInstanceOf(SQLException.class).hasMessageContaining("cache_size")
                    .satisfies(error -> assertThat(error.getSuppressed()).hasSize(3));
            assertThat(physical.isClosed()).isFalse();
            assertThatThrownBy(() -> new JdbcPublicationReadLimits(borrowed, limits))
                    .isInstanceOf(SQLException.class).hasMessageContaining("cache_size")
                    .satisfies(error -> assertThat(error.getSuppressed()).singleElement()
                            .satisfies(restore -> assertThat(restore.getSuppressed()).hasSize(3)));
            fail.set(false);
            // The borrower still owns the connection and can restore/use its normal policy.
            policy.close();
            assertThat(queryNumber(physical, "SELECT 1")).isOne();
        }
    }

    @Test
    void missingControlFactsAndSuppressedSqlWritesFailClosedWithoutPublishingAnIncompleteUnit() throws Exception {
        var tables = List.of("publication_unit", "publication_attempt", "publication_epoch");
        var errors = List.of("Missing experiment unit identity", "Missing attempt stamp", "Stale dependency snapshot");
        for (int index = 0; index < tables.size(); index++) {
            String table = tables.get(index), expected = errors.get(index);
            try (var fixture = new Fixture(java.nio.file.Files.createDirectory(directory.resolve("missing-" + index)))) {
                fixture.write(OperationClass.CONTROL, c -> {
                    JdbcPublicationTable.execute(c, "CREATE TRIGGER suppress_control BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(IGNORE); END");
                    return null;
                });
                assertThatThrownBy(() -> fixture.begin("incomplete-control", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L)))
                        .hasRootCauseMessage(expected);
                try (var c = fixture.dataSource.getConnection()) {
                    assertThat(queryNumber(c, "SELECT COUNT(*) FROM publication_unit")).isZero();
                }
            }
        }
        try (var fixture = new Fixture(directory)) {
            var attempt = fixture.begin("no-publication-sequence", 0, 0, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.seal(attempt);
            fixture.write(OperationClass.CONTROL, c -> { JdbcPublicationTable.execute(c, "DELETE FROM publication_sequence"); return null; });
            assertThatThrownBy(() -> fixture.publish(attempt, NOW, "no-receipt")).hasRootCauseMessage("Missing publication sequence");
            assertThat(fixture.receipt(attempt)).isEmpty();
        }
    }

    @Test
    void protocolRefusesUnboundedChunksAndTombstonesDoNotCreateValidityAnchors() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var attempt = fixture.begin("bounded-tombstone", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            var deletion = new Fact("rows", "masks", "deleted", null, attempt.unitId());
            assertThatThrownBy(() -> fixture.append(attempt, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.append(attempt, 0, java.util.Collections.nCopies(257, deletion)))
                    .isInstanceOf(IllegalArgumentException.class);
            fixture.append(attempt, 0, List.of(deletion));
            try (var c = fixture.dataSource.getConnection()) {
                c.setAutoCommit(false);
                assertThat(fixture.probe.readOwn(c, c, attempt, "rows", "masks", "deleted")).isEmpty();
                assertThat(queryNumber(c, "SELECT needs_validity FROM publication_unit")).isZero();
                c.rollback();
            }
            fixture.seal(attempt); fixture.publish(attempt, NOW, "tombstone-receipt");
            assertThat(fixture.receipt(attempt)).isPresent();
        }
    }

    @Test
    void privateIdInitializationFailsClosedWhenItsDurableAllocatorIsMissingOrUsesAnotherDirection() throws Exception {
        try (var fixture = new Fixture(directory)) {
            new SqliteUserVersionSchemaMigrator(fixture.dataSource, DataframeFormatMigrations.sqlite()).migrate();
            fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "CREATE TABLE idless(id INTEGER PRIMARY KEY AUTOINCREMENT)");
                JdbcPublicationTable.execute(c, "CREATE TABLE idless_history(history_id INTEGER PRIMARY KEY AUTOINCREMENT,former_row_id INTEGER)");
                return null;
            });
            var ids = new JdbcPrivateIdReservations(fixture.dataSource, java.time.Clock.systemUTC(), fixture.admission);
            ids.initialize("idless", 1);
            fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "UPDATE artifact_id_allocator SET strategy='DESCENDING' WHERE artifact='idless'"); return null;
            });
            assertThatThrownBy(() -> ids.initialize("idless", 1)).hasRootCauseMessage("Internal allocator identity drift");
            fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "DELETE FROM artifact_id_allocator WHERE artifact='idless'");
                JdbcPublicationTable.execute(c, "CREATE TRIGGER suppress_allocator BEFORE INSERT ON artifact_id_allocator BEGIN SELECT RAISE(IGNORE); END");
                return null;
            });
            assertThatThrownBy(() -> ids.initialize("idless", 1)).hasRootCauseMessage("Internal allocator identity drift");
            try (var c = fixture.dataSource.getConnection()) {
                var empty = new com.iocextractor.application.artifact.ArtifactIdReservation(
                        1, 0, com.iocextractor.application.artifact.ArtifactIdStrategy.ASCENDING);
                assertThatThrownBy(() -> JdbcPrivateIdReservations.seedPrivateSequence(c, "idless", empty))
                        .hasMessage("Private sequence requires an owned transaction");
                c.setAutoCommit(false);
                assertThatThrownBy(() -> JdbcPrivateIdReservations.seedPrivateSequence(c, "idless", empty))
                        .hasMessage("Private insertion needs a non-empty reservation");
                c.rollback();
            }
            assertThat(fixture.read("rows", "masks", "idless")).isEmpty();
        }
    }

    private static <T> T proxy(Class<T> type, T delegate, JdbcCall call) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> call.apply(method, arguments)));
    }

    private static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(delegate, arguments); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    @FunctionalInterface private interface JdbcCall {
        Object apply(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }

    @Test
    void nativeDmlCapturePreservesCountsConstraintsCascadesAndOnlyFinalChangedAddresses() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "CREATE TABLE capture_parent(id INTEGER PRIMARY KEY,value TEXT)");
                JdbcPublicationTable.execute(c, "CREATE TABLE capture_child(parent INTEGER REFERENCES capture_parent(id)"
                        + " ON DELETE CASCADE,source TEXT,count INTEGER,PRIMARY KEY(parent,source))");
                JdbcPublicationTable.execute(c, "INSERT INTO capture_parent VALUES(1,'old'),(2,'unchanged')");
                JdbcPublicationTable.execute(c, "INSERT INTO capture_child VALUES(1,'feed',3)");
                var parent = JdbcPublicationTable.inspect(c, "capture_parent");
                var child = JdbcPublicationTable.inspect(c, "capture_child");
                JdbcPrivateChangeCapture.install(c, List.of(parent, child));
                try (var statement = c.createStatement()) {
                    assertThat(preparedUpdate(c, "INSERT INTO capture_parent VALUES(2,'temporary')"
                            + " ON CONFLICT(id) DO UPDATE SET value=excluded.value")).isOne();
                    assertThat(preparedUpdate(c, "UPDATE capture_parent SET value='unchanged' WHERE id=2")).isOne();
                    assertThat(preparedUpdate(c, "INSERT INTO capture_parent VALUES(3,NULL)")).isOne();
                    assertThat(preparedUpdate(c, "DELETE FROM capture_parent WHERE id=1")).isOne();
                    assertThat(preparedUpdate(c, "INSERT INTO capture_parent VALUES(4,'transient')")).isOne();
                    assertThat(preparedUpdate(c, "DELETE FROM capture_parent WHERE id=4")).isOne();
                    assertThatThrownBy(() -> preparedUpdate(c, "INSERT INTO capture_parent VALUES(2,'duplicate')"))
                            .isInstanceOf(SQLException.class);
                }
                var parents = new ArrayList<JdbcPrivateChanges.Effect>();
                JdbcPrivateChanges.read(c, parent, JdbcPrivateChanges.Cursor.START, parents::add);
                assertThat(parents).hasSize(2);
                assertThat(parents).filteredOn(JdbcPrivateChanges.Effect::deleted).singleElement()
                        .satisfies(effect -> assertThat(effect.previous()).containsExactly(1, "old"));
                assertThat(parents).filteredOn(effect -> !effect.deleted()).singleElement()
                        .satisfies(effect -> {
                            assertThat(effect.values()).containsExactly(3, null);
                            assertThat(effect.previous()).isNull();
                        });
                var children = new ArrayList<JdbcPrivateChanges.Effect>();
                JdbcPrivateChanges.read(c, child, JdbcPrivateChanges.Cursor.START, children::add);
                assertThat(children).singleElement().satisfies(effect -> {
                    assertThat(effect.deleted()).isTrue();
                    assertThat(effect.previous()).containsExactly(1, "feed", 3);
                });
                return null;
            });
        }
    }

    @Test
    void nativeDmlCaptureMovesCompositeKeysAndRollsBackPrivateEffects() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var table = fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "CREATE TABLE capture_keys(a TEXT,b INTEGER,value TEXT,PRIMARY KEY(a,b))");
                JdbcPublicationTable.execute(c, "INSERT INTO capture_keys VALUES('same',1,'old')");
                var shape = JdbcPublicationTable.inspect(c, "capture_keys");
                JdbcPrivateChangeCapture.install(c, List.of(shape));
                return shape;
            });
            try (var c = fixture.dataSource.getConnection()) {
                assertThatThrownBy(() -> JdbcPrivateChanges.read(c, table, JdbcPrivateChanges.Cursor.START, ignored -> { }))
                        .isInstanceOf(IllegalArgumentException.class);
                c.setAutoCommit(false);
                JdbcPublicationTable.execute(c, "UPDATE capture_keys SET b=2,value='new'");
                var effects = new ArrayList<JdbcPrivateChanges.Effect>();
                JdbcPrivateChanges.read(c, table, JdbcPrivateChanges.Cursor.START, effects::add);
                assertThat(effects).hasSize(2);
                assertThat(effects).filteredOn(JdbcPrivateChanges.Effect::deleted).singleElement()
                        .satisfies(effect -> assertThat(effect.previous()).containsExactly("same", 1, "old"));
                assertThat(effects).filteredOn(effect -> !effect.deleted()).singleElement()
                        .satisfies(effect -> {
                            assertThat(effect.values()).containsExactly("same", 2, "new");
                            assertThat(effect.previous()).isNull();
                        });
                c.rollback();
                effects.clear();
                JdbcPrivateChanges.read(c, table, JdbcPrivateChanges.Cursor.START, effects::add);
                assertThat(effects).isEmpty();
                c.rollback();
            }
        }
    }

    @Test
    void sparseBeforeImagesPreserveOriginalAbsenceAcrossKeyMovesAndReinsertion() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.write(OperationClass.CONTROL, c -> {
                JdbcPublicationTable.execute(c, "CREATE TABLE sparse_keys(a TEXT,b INTEGER,value TEXT,PRIMARY KEY(a,b))");
                JdbcPublicationTable.execute(c, "INSERT INTO sparse_keys VALUES('old',1,'original')");
                var shape = JdbcPublicationTable.inspect(c, "sparse_keys");
                JdbcPrivateChangeCapture.install(c, List.of(shape));
                JdbcPublicationTable.execute(c, "INSERT INTO sparse_keys VALUES('new',2,'created')");
                JdbcPublicationTable.execute(c, "UPDATE sparse_keys SET b=3,value='moved' WHERE a='new'");
                JdbcPublicationTable.execute(c, "DELETE FROM sparse_keys WHERE a='old'");
                JdbcPublicationTable.execute(c, "INSERT INTO sparse_keys VALUES('old',1,'replaced')");
                var effects = new ArrayList<JdbcPrivateChanges.Effect>();
                JdbcPrivateChanges.read(c, shape, JdbcPrivateChanges.Cursor.START, effects::add);
                assertThat(effects).hasSize(2);
                assertThat(effects).filteredOn(effect -> effect.value("a").equals("new")).singleElement()
                        .satisfies(effect -> {
                            assertThat(effect.values()).containsExactly("new", 3, "moved");
                            assertThat(effect.previous()).isNull();
                        });
                assertThat(effects).filteredOn(effect -> effect.value("a").equals("old")).singleElement()
                        .satisfies(effect -> {
                            assertThat(effect.values()).containsExactly("old", 1, "replaced");
                            assertThat(effect.previous()).containsExactly("old", 1, "original");
                        });
                return null;
            });
        }
    }

    @Test
    void privateMutationJournalFoldsRenewalsAndStreamsBoundedIndexedPagesWithPinnedIdentity() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.write(OperationClass.PROMOTION, c -> {
                try (var journal = JdbcPrivateMutationJournal.open(c, "private-unit", PINS)) {
                    for (int index = 1; index <= 257; index++) {
                        journal.record("masks", outcome(index, index), false);
                    }
                    journal.record("masks", outcome(1, 1), true);
                    journal.record("masks", outcome(1, 1), false);
                    assertThatThrownBy(() -> journal.record("masks", outcome(1, 999), true))
                            .isInstanceOf(SQLException.class).hasMessage("Private mutation journal lifecycle identity changed");
                    assertThatThrownBy(() -> journal.record("", outcome(1, 1), true))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> journal.record("masks", null, true)).isInstanceOf(NullPointerException.class);
                }
                assertThatThrownBy(() -> JdbcPrivateMutationJournal.open(c, "other-unit", PINS))
                        .isInstanceOf(SQLException.class).hasMessage("Private mutation journal unit or pins changed");
                assertThatThrownBy(() -> JdbcPrivateMutationJournal.open(c, "private-unit", "changed-pins"))
                        .isInstanceOf(SQLException.class).hasMessage("Private mutation journal unit or pins changed");
                return null;
            });
            try (var snapshot = fixture.dataSource.getConnection()) {
                assertThatThrownBy(() -> JdbcPrivateMutationJournal.open(snapshot, "private-unit", PINS))
                        .isInstanceOf(SQLException.class).hasMessage("Private mutation journal requires an owned transaction");
                snapshot.setAutoCommit(false);
                var first = new ArrayList<JdbcPrivateMutationJournal.Entry>();
                var chunk = JdbcPrivateMutationJournal.readChunk(snapshot, 0, 256, first::add);
                assertThat(chunk.count()).isEqualTo(256);
                assertThat(first).hasSize(256);
                assertThat(first.getFirst().renewsValidity()).isTrue();
                assertThat(first.subList(1, first.size())).noneMatch(JdbcPrivateMutationJournal.Entry::renewsValidity);
                var last = new ArrayList<JdbcPrivateMutationJournal.Entry>();
                var tail = JdbcPrivateMutationJournal.readChunk(snapshot, chunk.lastOrdinal(), 256, last::add);
                assertThat(tail.count()).isOne();
                assertThat(last.getFirst().rowId()).isEqualTo(257);
                assertThat(JdbcPrivateMutationJournal.readChunk(snapshot, tail.lastOrdinal(), 256, ignored -> {
                    throw new AssertionError("Exhausted journal delivered an entry");
                })).isEqualTo(new JdbcPrivateMutationJournal.Chunk(tail.lastOrdinal(), 0));
                assertThatThrownBy(() -> JdbcPrivateMutationJournal.readChunk(snapshot, -1, 1, ignored -> { }))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> JdbcPrivateMutationJournal.readChunk(snapshot, 0, 0, ignored -> { }))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> JdbcPrivateMutationJournal.readChunk(snapshot, 0, 257, ignored -> { }))
                        .isInstanceOf(IllegalArgumentException.class);
                try (var journal = JdbcPrivateMutationJournal.open(snapshot, "private-unit", PINS)) {
                    // Opening the same pinned journal is safe; it does not reset prior effects.
                    assertThat(queryNumber(snapshot, "SELECT COUNT(*) FROM private_mutation_effect")).isEqualTo(257);
                }
                assertThat(snapshot.isClosed()).isFalse();
                assertThat(snapshot.getAutoCommit()).isFalse();
                snapshot.rollback();
            }
        }
    }

    @Test
    void privateMutationJournalRollsBackEffectsAndRejectsCrossThreadResourceUse() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.write(OperationClass.PROMOTION, c -> {
                try (var journal = JdbcPrivateMutationJournal.open(c, "private-unit", PINS)) {
                    journal.record("masks", outcome(1, 1), true);
                }
                return null;
            });
            assertThatThrownBy(() -> fixture.write(OperationClass.PROMOTION, c -> {
                try (var journal = JdbcPrivateMutationJournal.open(c, "private-unit", PINS)) {
                    journal.record("masks", outcome(2, 2), true);
                    throw new SQLException("Discard private chunk");
                }
            })).hasRootCauseMessage("Discard private chunk");
            try (var connection = fixture.dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var journal = JdbcPrivateMutationJournal.open(connection, "private-unit", PINS)) {
                    assertThat(queryNumber(connection, "SELECT COUNT(*) FROM private_mutation_effect")).isOne();
                    var worker = Executors.newSingleThreadExecutor();
                    try {
                        var checked = worker.submit(() -> {
                            assertThatThrownBy(() -> journal.record("masks", outcome(3, 3), true))
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessage("Private mutation journal belongs to another thread");
                            assertThatThrownBy(journal::close).isInstanceOf(IllegalStateException.class);
                        });
                        checked.get(5, TimeUnit.SECONDS);
                    } finally {
                        worker.shutdownNow();
                        assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                    }
                    journal.record("masks", outcome(4, 4), false);
                }
                assertThat(connection.isClosed()).isFalse();
                connection.rollback();
            }
        }
    }

    private static CanonicalRecordMutationOutcome outcome(long row, long lifecycle) {
        return new CanonicalRecordMutationOutcome(CanonicalRecordMutationKind.TTL_CONFIRMED,
                row, lifecycle, java.util.Set.of(), java.util.Set.of());
    }

    @Test
    void durableOwnershipFencesRestartAndBoundsConflictingPreparationWhileControlProgresses() throws Exception {
        var ownership = new JdbcPublicationOwner();
        JdbcPublicationOwner.Owner stale;
        Attempt last;
        try (var fixture = new Fixture(directory)) {
            fixture.write(OperationClass.CONTROL, c -> { ownership.initialize(c); return null; });
            stale = fixture.write(OperationClass.CONTROL, c -> ownership.acquire(c, "bounded-unit", 0));
            assertThatThrownBy(() -> fixture.write(OperationClass.CONTROL,
                    c -> ownership.acquire(c, "another-ready-unit", stale.fence())))
                    .hasRootCauseMessage("Logical promotion ownership changed or occupied");
            Attempt pending = null;
            for (int index = 0; index < 3; index++) {
                int expected = index + 1;
                assertThat((int) fixture.write(OperationClass.CONTROL, c -> ownership.nextAttempt(c, stale))).isEqualTo(expected);
                final Attempt previous = pending;
                final long epoch = index;
                pending = fixture.write(OperationClass.PROMOTION, c -> {
                    ownership.require(c, stale);
                    var manifest = new Manifest(1, 1, Long.MAX_VALUE, Map.of("masks", epoch));
                    return previous == null ? fixture.probe.begin(c, "bounded-unit", PINS, manifest)
                            : fixture.probe.rebuild(c, previous, PINS, manifest);
                });
                fixture.append(pending, 0, List.of(new Fact("row", "masks", "one", "pending")));
                fixture.seal(pending);
                fixture.write(OperationClass.CONTROL, c -> { fixture.probe.advanceEpoch(c, "masks"); return null; });
                final Attempt conflicting = pending;
                assertThatThrownBy(() -> fixture.publish(conflicting, NOW, "receipt"))
                        .hasRootCauseMessage("Dependency changed; rebuild required");
            }
            last = pending;
            assertThatThrownBy(() -> fixture.write(OperationClass.CONTROL, c -> ownership.nextAttempt(c, stale)))
                    .hasRootCauseMessage("Publication rebuild budget exhausted");
            assertThat(fixture.read("row", "masks", "one")).isEmpty();
        }
        // Recreate all process-local objects against the same physical database.
        try (var recovered = new Fixture(directory, false)) {
            var owner = recovered.write(OperationClass.CONTROL,
                    c -> ownership.acquire(c, "bounded-unit", stale.fence()));
            assertThatThrownBy(() -> recovered.write(OperationClass.PROMOTION,
                    c -> { ownership.require(c, stale); return null; }))
                    .hasRootCauseMessage("Stale logical promotion owner");
            assertThat((int) recovered.write(OperationClass.CONTROL, c -> ownership.nextAttempt(c, owner))).isOne();
            var attempt = recovered.write(OperationClass.PROMOTION, c -> {
                ownership.require(c, owner);
                return recovered.probe.rebuild(c, last, PINS,
                        new Manifest(1, 1, Long.MAX_VALUE, Map.of("masks", 3L)));
            });
            recovered.append(attempt, 0, List.of(new Fact("row", "masks", "one", "committed")));
            recovered.seal(attempt);
            recovered.write(OperationClass.CONTROL, c -> { recovered.probe.advanceEpoch(c, "control-only"); return null; });
            recovered.write(OperationClass.PROMOTION, c -> {
                ownership.require(c, owner);
                recovered.probe.publish(c, attempt, NOW, "receipt");
                ownership.release(c, owner);
                return null;
            });
            assertThat(recovered.read("row", "masks", "one")).contains("committed");
            assertThat(recovered.receipt(attempt)).isPresent();
            assertThat(recovered.write(OperationClass.CONTROL,
                    c -> ownership.acquire(c, "next-ready-unit", owner.fence())).fence()).isEqualTo(owner.fence() + 1);
        }
    }

    @Test
    void realKernelWorksOnSelectedRowsAndPublishesTypedFactsToTheExistingMatcher() throws Exception {
        Instant start = Instant.ofEpochMilli(NOW);
        try (var experiment = new SqliteKernelPublicationExperiment(directory, start)) {
            var original = experiment.register("typed-original");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", original);
                base.rollback();
            }
            var inserted = experiment.confirm("one.example", "first", "source:A", 11, 101, original, 0);
            var repeated = experiment.confirm("one.example", "winner", "source:A", 12, 102, original, 1);
            assertThat(inserted.kind()).isEqualTo(CanonicalRecordMutationKind.INSERTED);
            assertThat(repeated.kind()).isEqualTo(CanonicalRecordMutationKind.UPDATED);
            assertThat(repeated.canonicalRowId()).isEqualTo(11);
            var pending = experiment.stage("typed-unit", "one.example", 0, Long.MAX_VALUE);
            try (var pinned = experiment.published.getConnection()) {
                pinned.setAutoCommit(false);
                assertThat(experiment.match(pinned, "one.example", start).cardinality())
                        .isEqualTo(CanonicalMatchCardinality.ZERO);
                experiment.wallClock.advance(Duration.ofSeconds(10));
                var receipt = experiment.publish(pending);
                assertThat(receipt.effectiveMillis()).isEqualTo(NOW + 10_000);
                assertThat(experiment.match(pinned, "one.example", start.plusSeconds(10)).cardinality())
                        .isEqualTo(CanonicalMatchCardinality.ZERO);
                pinned.rollback();
                assertThat(experiment.match(pinned, "one.example", start.plusSeconds(10)).exactCandidate())
                        .hasValueSatisfying(candidate -> {
                            assertThat(candidate.canonicalRowId()).isEqualTo(11);
                            assertThat(candidate.lifecycleId()).isEqualTo(101);
                        });
                try (var statement = pinned.prepareStatement(
                        new JdbcCanonicalStoredRowReader(SqliteKernelPublicationExperiment.SCHEMA).sql())) {
                    var row = new JdbcCanonicalStoredRowReader(SqliteKernelPublicationExperiment.SCHEMA)
                            .load(statement, 11);
                    assertThat(row.publicRow().value("name")).isEqualTo("winner");
                    assertThat(row.validUntilEpochMs()).isEqualTo(NOW + 10_000 + Duration.ofHours(1).toMillis());
                }
                assertThat(queryNumber(pinned, "SELECT occurrences FROM masks_sources WHERE row_id=11"))
                        .isEqualTo(2);
                assertThat(queryNumber(pinned, "SELECT occurrence_position FROM canonical_lifecycle_field_origin "
                        + "WHERE field_name='name'" )).isEqualTo(1);
                assertThat(queryNumber(pinned, "SELECT _first_confirmed_at_epoch_ms FROM masks WHERE id=11"))
                        .isEqualTo(receipt.effectiveMillis());
                pinned.rollback();
                experiment.wallClock.advance(Duration.ofMinutes(-1));
                // Committed recovery uses the receipt, even when fresh UTC would be unsafe.
                assertThat(experiment.publish(pending)).isEqualTo(receipt);
            }
        }
    }

    private static long queryNumber(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"100,170,140", "170,100,140", "100,140,170"})
    void idlessReservationsPreserveSequenceHistoryAndBurnedRangesBeforePrivateKernelInsertion(
            long activeId, long formerId, long sequence) throws Exception {
        var schema = new DataframeArtifactSchema("blacklist", List.of(
                new DataframeColumn("url"), new DataframeColumn("source")));
        var definition = new com.iocextractor.application.artifact.CanonicalKeyDefinition("url-v1",
                com.iocextractor.application.artifact.CanonicalKeyMode.COMPOSITE, List.of("url"));
        var identity = new com.iocextractor.application.artifact.ArtifactIdentityDefinition(
                "blacklist", definition, List.of(definition), 1);
        var time = com.iocextractor.application.artifact.lifecycle.EffectiveTime.at(Instant.ofEpochMilli(NOW));
        var validity = new FixedRecordValidityPolicy(Duration.ofHours(1));
        try (var canonical = new Fixture(directory);
             var privateBase = new Fixture(java.nio.file.Files.createDirectory(directory.resolve("private-idless")))) {
            for (var fixture : List.of(canonical, privateBase)) {
                new SqliteUserVersionSchemaMigrator(fixture.dataSource, DataframeFormatMigrations.sqlite()).migrate();
                new DataframeSchemaReconciler(fixture.dataSource).reconcile(List.of(schema));
                new JdbcArtifactIdentityStore(fixture.dataSource, java.time.Clock.systemUTC()).ensure(identity);
            }
            var row = com.iocextractor.application.artifact.ArtifactRow.ordered(
                    Map.of("url", "https://one.example/path", "source", "feed"));
            var keys = new com.iocextractor.application.artifact.CanonicalArtifactKeyResolver(List.of(identity));
            var recordKey = new com.iocextractor.application.artifact.ArtifactRowKey(
                    keys.recordKeyOf("blacklist", row).orElseThrow().keyHash());
            var kernel = new JdbcCanonicalMutationEngine(canonical.dataSource, List.of(schema), List.of(identity));
            try (var c = canonical.dataSource.getConnection()) {
                c.setAutoCommit(false);
                var original = kernel.insertPlanned(c, schema, "feed", row, recordKey,
                        new com.iocextractor.application.artifact.lifecycle.LifecycleId(1), time, validity.decide(time));
                new JdbcLifecycleArchive().archiveAndDelete(c, schema, original.canonicalRowId(), time);
                var activeRow = row.withValue("url", "https://two.example/path");
                var activeKey = new com.iocextractor.application.artifact.ArtifactRowKey(
                        keys.recordKeyOf("blacklist", activeRow).orElseThrow().keyHash());
                var active = kernel.insertPlanned(c, schema, "feed", activeRow, activeKey,
                        new com.iocextractor.application.artifact.lifecycle.LifecycleId(2), time, validity.decide(time));
                // Independently restored legacy high-water fixtures exercise each input to the maximum.
                try (var s = c.createStatement()) {
                    s.execute("PRAGMA defer_foreign_keys=ON");
                    s.executeUpdate("UPDATE blacklist SET id=" + activeId + " WHERE id=" + active.canonicalRowId());
                    s.executeUpdate("UPDATE blacklist_sources SET row_id=" + activeId + " WHERE row_id=" + active.canonicalRowId());
                    s.executeUpdate("UPDATE canonical_match_alias SET canonical_row_id=" + activeId
                            + " WHERE artifact='blacklist' AND canonical_row_id=" + active.canonicalRowId());
                    s.executeUpdate("UPDATE blacklist_history SET former_row_id=" + formerId);
                    s.executeUpdate("UPDATE sqlite_sequence SET seq=" + sequence + " WHERE name='blacklist'");
                }
                c.commit();
            }
            var reservations = new JdbcPrivateIdReservations(canonical.dataSource,
                    java.time.Clock.fixed(time.value(), java.time.ZoneOffset.UTC), canonical.admission);
            reservations.initialize("blacklist", 1);
            var burned = reservations.reserve("blacklist", 3);
            assertThat(burned.idAt(0)).isEqualTo(171);
            assertThat(burned.idAt(2)).isEqualTo(173);
            try (var c = canonical.dataSource.getConnection(); var s = c.createStatement()) {
                s.executeUpdate("DELETE FROM blacklist_history");
            }
            // Reinitialization after retained history removal cannot recover the burned IDs.
            reservations.initialize("blacklist", 1);
            var reserved = reservations.reserve("blacklist", 1);
            assertThat(reserved.idAt(0)).isEqualTo(174);
            var privateKernel = new JdbcCanonicalMutationEngine(privateBase.dataSource, List.of(schema), List.of(identity));
            try (var c = privateBase.dataSource.getConnection()) {
                c.setAutoCommit(false);
                JdbcPrivateIdReservations.seedPrivateSequence(c, "blacklist", reserved);
                var inserted = privateKernel.insertPlanned(c, schema, "feed", row, recordKey,
                        new com.iocextractor.application.artifact.lifecycle.LifecycleId(2), time, validity.decide(time));
                assertThat(inserted.canonicalRowId()).isEqualTo(174);
                c.commit();
            }
            // IDs are authoritative in the dataframe reservation store, not the private database.
            var reopened = new JdbcPrivateIdReservations(canonical.dataSource,
                    java.time.Clock.systemUTC(), canonical.admission);
            reopened.initialize("blacklist", 1);
            assertThat(reopened.reserve("blacklist", 1).idAt(0)).isEqualTo(175);
            try (var c = privateBase.dataSource.getConnection()) {
                c.setAutoCommit(false);
                assertThatThrownBy(() -> JdbcPrivateIdReservations.seedPrivateSequence(c, "blacklist", burned))
                        .isInstanceOf(SQLException.class).hasMessage("Private sequence exceeds reserved range");
                c.rollback();
            }
            assertThatThrownBy(() -> reopened.initialize("blacklist", 2))
                    .hasRootCauseMessage("Internal allocator identity drift");
        }
    }

    @Test
    void realKernelRebuildsAtPublicationDeadlineAndReplacesExpiredLifecycle() throws Exception {
        Instant start = Instant.ofEpochMilli(NOW);
        try (var experiment = new SqliteKernelPublicationExperiment(directory, start)) {
            var first = experiment.register("deadline-original");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", first);
                base.rollback();
            }
            experiment.confirmReserved("one.example", "initial", "source:A", first, 0);
            var baseUnit = experiment.stage("deadline-base", "one.example", 0, Long.MAX_VALUE);
            experiment.publish(baseUnit);
            var incoming = experiment.register("deadline-incoming");
            experiment.wallClock.advance(Duration.ofHours(1).minusMillis(1));
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", incoming);
                base.rollback();
            }
            var premature = experiment.confirmReserved("one.example", "updated", "source:B", incoming, 0);
            assertThat(premature.kind()).isEqualTo(CanonicalRecordMutationKind.UPDATED);
            assertThat(premature.lifecycleId()).isEqualTo(1);
            long deadline = NOW + Duration.ofHours(1).toMillis();
            var pending = experiment.stage("deadline-incoming", "one.example", 1, deadline);
            experiment.wallClock.advance(Duration.ofMillis(1));
            assertThatThrownBy(() -> experiment.publish(pending))
                    .hasRootCauseMessage("Incomplete, expired or stale publication attempt");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                assertThat(queryNumber(base, "SELECT COUNT(*) FROM publication_unit WHERE state='COMMITTED'"))
                        .isEqualTo(1);
                assertThat(queryNumber(base, "SELECT COUNT(*) FROM masks_history" )).isZero();
                assertThat(queryNumber(base, "SELECT safe_time_high_water_ms FROM canonical_lifecycle_control"))
                        .isEqualTo(NOW);
                assertThat(experiment.match(base, "one.example", experiment.wallClock.instant()).cardinality())
                        .isEqualTo(CanonicalMatchCardinality.ZERO);
                experiment.hydrate(base, "one.example", incoming);
                base.rollback();
            }
            var restarted = experiment.confirmReserved("one.example", "updated", "source:B", incoming, 0);
            assertThat(restarted.kind()).isEqualTo(CanonicalRecordMutationKind.RESTARTED);
            assertThat(restarted.canonicalRowId()).isEqualTo(13);
            assertThat(restarted.lifecycleId()).isEqualTo(3);
            var rebuilt = experiment.restage(pending, "one.example", 1, Long.MAX_VALUE);
            assertThat(rebuilt.unitId()).isEqualTo(pending.unitId());
            assertThat(rebuilt.fence()).isGreaterThan(pending.fence());
            try (var c = experiment.published.getConnection()) {
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_history" )).isZero();
                assertThat(queryNumber(c, "SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'"))
                        .isEqualTo(14);
                assertThat(queryNumber(c, "SELECT next_value FROM lifecycle_id_allocator" )).isEqualTo(4);
            }
            var receipt = experiment.publish(rebuilt);
            assertThat(receipt.effectiveMillis()).isEqualTo(deadline);
            assertThatThrownBy(() -> experiment.publish(pending))
                    .hasRootCauseMessage("Stale attempt or invalid publication state");
            try (var c = experiment.published.getConnection()) {
                assertThat(experiment.match(c, "one.example", experiment.wallClock.instant()).exactCandidate())
                        .hasValueSatisfying(candidate -> assertThat(candidate.lifecycleId()).isEqualTo(3));
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks WHERE id=11" )).isZero();
                assertThat(queryNumber(c, "SELECT occurrences FROM masks_sources WHERE row_id=13"))
                        .isEqualTo(1);
                assertThat(queryNumber(c, "SELECT _first_confirmed_at_epoch_ms FROM masks WHERE id=13"))
                        .isEqualTo(deadline);
                assertThat(queryNumber(c, "SELECT _lifecycle_id FROM masks_history WHERE former_row_id=11"))
                        .isEqualTo(1);
                assertThat(queryNumber(c, "SELECT occurrences FROM masks_history_sources" )).isEqualTo(1);
                assertThat(queryNumber(c, "SELECT closed_at_epoch_ms FROM masks_history" )).isEqualTo(deadline);
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM canonical_lifecycle_field_origin_history "
                        + "WHERE lifecycle_id=1 AND field_name='name'" )).isEqualTo(1);
            }
            try (var c = experiment.working.getConnection()) {
                assertThat(queryNumber(c, "SELECT _lifecycle_id FROM masks_history WHERE former_row_id=11"))
                        .isEqualTo(1);
                assertThat(queryNumber(c, "SELECT occurrences FROM masks_history_sources" )).isEqualTo(1);
            }
            experiment.wallClock.advance(Duration.ofHours(1));
            var afterPrivateReset = experiment.register("deadline-private-reset");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", afterPrivateReset);
                base.rollback();
            }
            try (var c = experiment.working.getConnection(); var s = c.createStatement()) {
                // A recreated selective workspace has no knowledge of published history IDs.
                s.executeUpdate("DELETE FROM sqlite_sequence WHERE name='masks_history'");
            }
            experiment.confirmReserved("one.example", "after-reset", "source:C", afterPrivateReset, 0);
            var resetUnit = experiment.stage("after-private-reset", "one.example", 2, Long.MAX_VALUE);
            experiment.publish(resetUnit);
            try (var c = experiment.published.getConnection()) {
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_history")).isEqualTo(2);
                assertThat(queryNumber(c, "SELECT COUNT(DISTINCT history_id) FROM masks_history")).isEqualTo(2);
                assertThat(queryNumber(c, "SELECT history_id FROM masks_history WHERE former_row_id=11")).isEqualTo(3);
                assertThat(queryNumber(c, "SELECT history_id FROM masks_history WHERE former_row_id=13")).isEqualTo(4);
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_history_sources")).isEqualTo(2);
            }
        }
    }

    @Test
    void selectiveKernelHydrationPreservesUnrelatedRowsAndEarlierSourceAnchors() throws Exception {
        Instant start = Instant.ofEpochMilli(NOW);
        try (var experiment = new SqliteKernelPublicationExperiment(directory, start)) {
            var older = experiment.register("selective-older");
            var newer = experiment.register("selective-newer");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", newer);
                base.rollback();
            }
            experiment.confirm("one.example", "winner", "source:A", 11, 101, newer, 0);
            experiment.publish(experiment.stage("selective-one", "one.example", 0, Long.MAX_VALUE));
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "unrelated.example", newer);
                base.rollback();
            }
            experiment.confirm("unrelated.example", "unrelated", "source:B", 21, 201, newer, 1);
            experiment.publish(experiment.stage("selective-other", "unrelated.example", 1, Long.MAX_VALUE));
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", older);
                base.rollback();
            }
            try (var c = experiment.working.getConnection()) {
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks" )).isEqualTo(1);
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks WHERE id=21" )).isZero();
            }
            // Equal clock values cannot be used to infer whether a source was confirmed.
            var confirmed = experiment.confirm("one.example", "stale", "source:A", 31, 301, older, 50);
            assertThat(confirmed.kind()).isEqualTo(CanonicalRecordMutationKind.TTL_CONFIRMED);
            assertThat(confirmed.publicMutation()).isFalse();
            assertThat(confirmed.metadataMutation()).isFalse();
            var attempt = experiment.stage("selective-renewal", "one.example", 2,
                    NOW + Duration.ofHours(1).toMillis());
            experiment.wallClock.advance(Duration.ofSeconds(5));
            experiment.publish(attempt);
            try (var c = experiment.published.getConnection()) {
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks" )).isEqualTo(2);
                assertThat(experiment.match(c, "unrelated.example", experiment.wallClock.instant()).exactCandidate())
                        .hasValueSatisfying(candidate -> assertThat(candidate.canonicalRowId()).isEqualTo(21));
                assertThat(queryNumber(c, "SELECT _first_confirmed_at_epoch_ms FROM masks WHERE id=11"))
                        .isEqualTo(NOW);
                assertThat(queryNumber(c, "SELECT _last_confirmed_at_epoch_ms FROM masks WHERE id=11"))
                        .isEqualTo(NOW + 5_000);
                assertThat(queryNumber(c, "SELECT occurrences FROM masks_sources WHERE row_id=11"))
                        .isEqualTo(2);
                try (var statement = c.createStatement(); var rows = statement.executeQuery(
                        "SELECT first_seen_at,last_seen_at FROM masks_sources WHERE row_id=11")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(Instant.parse(rows.getString(1))).isEqualTo(start);
                    assertThat(Instant.parse(rows.getString(2))).isEqualTo(start.plusSeconds(5));
                }
                assertThat(queryNumber(c, "SELECT admission_order FROM canonical_lifecycle_field_origin "
                        + "WHERE lifecycle_id=101 AND field_name='name'"))
                        .isEqualTo(newer.admissionOrder().value());
            }
        }
    }

    @Test
    void typedSnapshotBoundsIncludeAllFamiliesAndRollbackOversizedStaging() throws Exception {
        try (var experiment = new SqliteKernelPublicationExperiment(directory, Instant.ofEpochMilli(NOW))) {
            var registered = experiment.register("bounded-typed");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", registered);
                base.rollback();
            }
            experiment.confirm("one.example", "name", "source:A", 11, 101, registered, 0);
            try (var c = experiment.working.getConnection(); var statement = c.createStatement()) {
                statement.executeUpdate("""
                        WITH RECURSIVE counts(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM counts WHERE value<254)
                        INSERT INTO masks_sources(row_id,source_key,first_seen_at,last_seen_at,occurrences)
                        SELECT 11,'fixture:'||value,'2027-01-15T00:00:00Z','2027-01-15T00:00:00Z',1 FROM counts
                        """);
            }
            assertThatThrownBy(() -> experiment.stage("over-row-bound", "one.example", 0, Long.MAX_VALUE))
                    .hasRootCauseMessage("Typed snapshot total row bound exceeded");
            try (var c = experiment.working.getConnection(); var statement = c.createStatement()) {
                statement.executeUpdate("DELETE FROM masks_sources WHERE source_key LIKE 'fixture:%'");
                try (var update = c.prepareStatement("UPDATE masks SET name=? WHERE id=11")) {
                    update.setString(1, "ж".repeat(600_000));
                    update.executeUpdate();
                }
            }
            assertThatThrownBy(() -> experiment.stage("over-byte-bound", "one.example", 0, Long.MAX_VALUE))
                    .hasRootCauseMessage("Typed snapshot byte bound exceeded");
            try (var c = experiment.published.getConnection()) {
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM publication_unit" )).isZero();
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_probe_version" )).isZero();
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks" )).isZero();
            }
        }
    }

    @Test
    void realImportKernelPatchChangesFieldsAndProvenanceWithoutRenewingValidity() throws Exception {
        Instant start = Instant.ofEpochMilli(NOW);
        try (var experiment = new SqliteKernelPublicationExperiment(directory, start)) {
            var original = experiment.register("patch-original");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", original);
                base.rollback();
            }
            experiment.confirmReserved("one.example", "original", "source:A", original, 0);
            experiment.publish(experiment.stage("patch-base", "one.example", 0, Long.MAX_VALUE));
            experiment.wallClock.advance(Duration.ofSeconds(10));
            var imported = experiment.register("patch-import", ObservationOrigin.MANAGED_IMPORT);
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", imported);
                base.rollback();
            }
            var patch = experiment.patch(11, "one.example", "patched", "source:B", imported);
            assertThat(patch.kind()).isEqualTo(CanonicalRecordMutationKind.UPDATED);
            assertThat(patch.updatedFields()).containsExactlyInAnyOrder("name", "source");
            var attempt = experiment.stagePatch("patch-unit", "one.example", 1);
            experiment.wallClock.advance(Duration.ofSeconds(10));
            var receipt = experiment.publish(attempt);
            assertThat(receipt.effectiveMillis()).isEqualTo(NOW + 20_000);
            try (var c = experiment.published.getConnection(); var statement = c.createStatement();
                 var rows = statement.executeQuery("SELECT name,_first_confirmed_at_epoch_ms,"
                         + "_last_confirmed_at_epoch_ms,_valid_until_epoch_ms FROM masks WHERE id=11")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("patched");
                assertThat(rows.getLong(2)).isEqualTo(NOW);
                assertThat(rows.getLong(3)).isEqualTo(NOW);
                assertThat(rows.getLong(4)).isEqualTo(NOW + Duration.ofHours(1).toMillis());
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_sources WHERE row_id=11"))
                        .isEqualTo(2);
                assertThat(queryNumber(c, "SELECT occurrences FROM masks_sources WHERE row_id=11 AND source_key='source:A'"))
                        .isEqualTo(1);
                assertThat(queryNumber(c, "SELECT admission_order FROM canonical_lifecycle_field_origin "
                        + "WHERE lifecycle_id=1 AND field_name='name'"))
                        .isEqualTo(imported.admissionOrder().value());
            }
        }
    }

    @Test
    void publishesLargeSourceFanInThroughBoundedTypedChunksAndRehydratesAllChildren() throws Exception {
        Instant start = Instant.ofEpochMilli(NOW);
        try (var experiment = new SqliteKernelPublicationExperiment(directory, start)) {
            var registration = experiment.register("large-source-fan-in");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", registration);
                base.rollback();
            }
            for (int source = 0; source < 520; source++) {
                experiment.confirm("one.example", "name", "source:" + source,
                        11 + source, 101 + source, registration, source);
            }
            var chunks = new java.util.concurrent.atomic.AtomicInteger();
            var pending = experiment.stageChunks("large-typed", "one.example", 0, (attempt, ordinal) -> {
                chunks.incrementAndGet();
                // A real CONTROL transaction must commit after each released physical chunk.
                experiment.admission.execute(OperationClass.CONTROL, () -> {
                    try (var c = experiment.published.getConnection()) {
                        c.setAutoCommit(false);
                        new JdbcPublicationProtocol().advanceEpoch(c, "control-only");
                        assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks")).isZero();
                        assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_sources")).isZero();
                        c.commit();
                    } catch (SQLException failure) {
                        throw new AssertionError(failure);
                    }
                    return null;
                });
                try {
                    experiment.retryChunk(attempt, ordinal - 1);
                    if (ordinal == 1) {
                        try (var c = experiment.working.getConnection(); var s = c.createStatement()) {
                            s.executeUpdate("UPDATE masks SET name='changed-retry'");
                        }
                        try {
                            assertThatThrownBy(() -> experiment.retryChunk(attempt, 0))
                                    .hasRootCauseMessage("Changed chunk retry");
                        } finally {
                            try (var c = experiment.working.getConnection(); var s = c.createStatement()) {
                                s.executeUpdate("UPDATE masks SET name='name'");
                            }
                        }
                    }
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            });
            assertThat(chunks.get()).isEqualTo(3);
            experiment.wallClock.advance(Duration.ofSeconds(3));
            experiment.publish(pending);
            var next = experiment.register("large-source-update");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                assertThat(queryNumber(base, "SELECT COUNT(*) FROM masks_sources")).isEqualTo(520);
                assertThat(queryNumber(base, "SELECT COUNT(*) FROM masks_sources_probe_version")).isEqualTo(520);
                assertThat(queryNumber(base, "SELECT SUM(occurrences) FROM masks_sources")).isEqualTo(520);
                assertThat(queryNumber(base, "SELECT MAX(_last_confirmed_at_epoch_ms) FROM masks"))
                        .isEqualTo(NOW + 3_000);
                experiment.hydrate(base, "one.example", next);
                base.rollback();
            }
            experiment.confirm("one.example", "new-name", "source:0", 1_000, 1_000, next, 0);
            var update = experiment.stageChunks("large-typed-update", "one.example", 1, (attempt, ordinal) -> { });
            experiment.publish(update);
            try (var c = experiment.published.getConnection()) {
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_sources")).isEqualTo(520);
                assertThat(queryNumber(c, "SELECT SUM(occurrences) FROM masks_sources")).isEqualTo(521);
                assertThat(queryNumber(c, "SELECT occurrences FROM masks_sources WHERE source_key='source:0'"))
                        .isEqualTo(2);
                assertThat(experiment.match(c, "one.example", start.plusSeconds(3)).exactCandidate())
                        .hasValueSatisfying(candidate -> assertThat(candidate.canonicalRowId()).isEqualTo(11));
            }
        }
    }

    @Test
    void nonRenewingPatchAutomaticallyPinsItsInheritedDeadline() throws Exception {
        Instant start = Instant.ofEpochMilli(NOW);
        try (var experiment = new SqliteKernelPublicationExperiment(directory, start)) {
            var original = experiment.register("expired-patch-original");
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", original);
                base.rollback();
            }
            experiment.confirmReserved("one.example", "original", "source:A", original, 0);
            experiment.publish(experiment.stage("expired-patch-base", "one.example", 0, Long.MAX_VALUE));
            experiment.wallClock.advance(Duration.ofHours(1).minusMillis(1));
            var imported = experiment.register("expired-patch-import", ObservationOrigin.MANAGED_IMPORT);
            try (var base = experiment.published.getConnection()) {
                base.setAutoCommit(false);
                experiment.hydrate(base, "one.example", imported);
                base.rollback();
            }
            experiment.patch(11, "one.example", "must-stay-hidden", "source:B", imported);
            var attempt = experiment.stagePatch("expired-patch-unit", "one.example", 1);
            experiment.wallClock.advance(Duration.ofMillis(1));
            assertThatThrownBy(() -> experiment.publish(attempt))
                    .hasRootCauseMessage("Incomplete, expired or stale publication attempt");
            try (var c = experiment.published.getConnection(); var statement = c.createStatement();
                 var rows = statement.executeQuery("SELECT name FROM masks WHERE id=11")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("original");
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM masks_sources WHERE source_key='source:B'"))
                        .isZero();
                assertThat(queryNumber(c, "SELECT COUNT(*) FROM publication_unit WHERE state='COMMITTED'"))
                        .isEqualTo(1);
                assertThat(experiment.match(c, "one.example", experiment.wallClock.instant()).cardinality())
                        .isEqualTo(CanonicalMatchCardinality.ZERO);
            }
        }
    }

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
            assertThat(fixture.receipt(unit)).contains(new JdbcPublicationProtocol.Receipt(1, NOW, "receipt"));
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
            assertThat(fixture.receipt(empty)).contains(new JdbcPublicationProtocol.Receipt(
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
    void anchorsDeadlineAtPublicationUsingTheRealValidityPolicyAndRequiresStrictActivity() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt unit = fixture.begin("deadline", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(unit, 0, List.of(new Fact("row", "masks", "key", "value", unit.unitId())));
            fixture.seal(unit);
            assertThatThrownBy(() -> fixture.publish(unit, NOW, "receipt"))
                    .hasRootCauseInstanceOf(SQLException.class);
            assertThat(fixture.receipt(unit)).isEmpty();
            assertThat(fixture.publishWithValidity(unit, NOW + 1_000, "receipt", Duration.ofHours(1)).sequence())
                    .isEqualTo(1);
            long deadline = NOW + 1_000 + Duration.ofHours(1).toMillis();
            try (Connection connection = fixture.dataSource.getConnection()) {
                assertThat(fixture.probe.readActive(connection, "row", "masks", "key", deadline - 1))
                        .contains("value");
                assertThat(fixture.probe.readActive(connection, "row", "masks", "key", deadline)).isEmpty();
                assertThat(fixture.probe.nearest(connection, "row", "masks", NOW)).hasValue(deadline);
                assertThat(fixture.probe.nearest(connection, "row", "masks", deadline)).isEmpty();
            }
        }
    }

    @Test
    void expirationOfNewestVersionCannotRevealAnOlderVersionWithLongerValidity() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt initial = fixture.begin("initial", 1, 2, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(initial, 0, List.of(new Fact("row", "masks", "renew", "old", initial.unitId()),
                    new Fact("row", "masks", "survivor", "kept", initial.unitId())));
            fixture.seal(initial);
            fixture.publishWithValidity(initial, NOW, "initial-receipt", Duration.ofHours(2));
            Attempt newer = fixture.begin("newer", 1, 1, Long.MAX_VALUE, Map.of("masks", 1L));
            fixture.append(newer, 0, List.of(new Fact("row", "masks", "renew", "new", newer.unitId())));
            fixture.seal(newer);
            fixture.publishWithValidity(newer, NOW, "newer-receipt", Duration.ofHours(1));
            long asOf = NOW + Duration.ofHours(1).toMillis();
            try (Connection connection = fixture.dataSource.getConnection()) {
                assertThat(fixture.probe.readActive(connection, "row", "masks", "renew", asOf)).isEmpty();
                assertThat(fixture.probe.readActive(connection, "row", "masks", "survivor", asOf))
                        .contains("kept");
                assertThat(fixture.probe.nearest(connection, "row", "masks", asOf))
                        .hasValue(NOW + Duration.ofHours(2).toMillis());
            }
            Attempt deletion = fixture.begin("delete", 1, 1, Long.MAX_VALUE, Map.of("masks", 2L));
            fixture.append(deletion, 0, List.of(new Fact("row", "masks", "survivor", null)));
            fixture.seal(deletion);
            fixture.publish(deletion, asOf, "delete-receipt");
            try (Connection connection = fixture.dataSource.getConnection()) {
                assertThat(fixture.probe.nearest(connection, "row", "masks", asOf)).isEmpty();
            }
        }
    }

    @Test
    void inheritedDeadlineIsNotRenewedAndItsExpiryAutomaticallyInvalidatesPublication() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt initial = fixture.begin("initial", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            fixture.append(initial, 0, List.of(new Fact("row", "masks", "key", "original", initial.unitId())));
            fixture.seal(initial);
            fixture.publishWithValidity(initial, NOW, "initial-receipt", Duration.ofHours(1));
            Attempt patch = fixture.begin("patch", 1, 1, Long.MAX_VALUE, Map.of("masks", 1L));
            fixture.append(patch, 0, List.of(new Fact("row", "masks", "key", "patch", initial.unitId())));
            fixture.seal(patch);
            fixture.publish(patch, NOW + 1_000, "patch-receipt");
            long deadline = NOW + Duration.ofHours(1).toMillis();
            try (Connection connection = fixture.dataSource.getConnection();
                 var plan = connection.prepareStatement("EXPLAIN QUERY PLAN " + JdbcPublicationProtocol.NEAREST_SQL)) {
                assertThat(fixture.probe.nearest(connection, "row", "masks", NOW)).hasValue(deadline);
                assertThat(fixture.probe.readActive(connection, "row", "masks", "key", deadline)).isEmpty();
                plan.setLong(1, NOW); plan.setString(2, "row"); plan.setString(3, "masks");
                List<String> details = new ArrayList<>();
                try (var result = plan.executeQuery()) {
                    while (result.next()) { details.add(result.getString("detail")); }
                }
                assertThat(details).anyMatch(detail -> detail.contains("publication_unit_deadline"));
                assertThat(details).anyMatch(detail -> detail.contains("publication_fact_deadline"));
                assertThat(details).noneMatch(detail -> detail.contains("USE TEMP B-TREE FOR ORDER BY"));
            }
            Attempt expiredPatch = fixture.begin("expired-patch", 1, 1, Long.MAX_VALUE, Map.of("masks", 2L));
            fixture.append(expiredPatch, 0,
                    List.of(new Fact("row", "masks", "key", "too-late", initial.unitId())));
            fixture.seal(expiredPatch);
            assertThatThrownBy(() -> fixture.publish(expiredPatch, deadline, "expired-receipt"))
                    .hasRootCauseMessage("Incomplete, expired or stale publication attempt");
            assertThat(fixture.receipt(expiredPatch)).isEmpty();
            assertThat(fixture.read("row", "masks", "key")).contains("patch");
        }
    }

    @Test
    void cannotInheritAnUncommittedOrMissingDeadlineAnchor() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Attempt pendingAnchor = fixture.begin("pending-anchor", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            Attempt unit = fixture.begin("delivery", 1, 1, Long.MAX_VALUE, Map.of("masks", 0L));
            assertThatThrownBy(() -> fixture.append(unit, 0,
                    List.of(new Fact("row", "masks", "key", "value", pendingAnchor.unitId()))))
                    .hasRootCauseMessage("Missing committed deadline anchor");
            assertThatThrownBy(() -> fixture.append(unit, 0,
                    List.of(new Fact("row", "masks", "key", "value", Long.MAX_VALUE))))
                    .hasRootCauseMessage("Missing committed deadline anchor");
            fixture.append(unit, 0, List.of(new Fact("row", "masks", "key", "value", unit.unitId())));
            fixture.seal(unit);
            fixture.publishWithValidity(unit, NOW, "receipt", Duration.ofHours(1));
            assertThat(fixture.read("row", "masks", "key")).contains("value");
        }
    }

    @Test
    void releasesPhysicalAdmissionBetweenChunksForControlAndUsesSelectiveLookup() throws Exception {
        try (var fixture = new Fixture(directory)) {
            int total = JdbcPublicationProtocol.CHUNK_FACT_LIMIT * 16;
            Attempt unit = fixture.begin("bulk", 16, total, Long.MAX_VALUE, Map.of("masks", 0L));
            CountDownLatch firstChunk = new CountDownLatch(1);
            CountDownLatch controlFinished = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var worker = executor.submit(() -> {
                    for (int ordinal = 0; ordinal < 16; ordinal++) {
                        List<Fact> facts = new ArrayList<>();
                        for (int index = 0; index < JdbcPublicationProtocol.CHUNK_FACT_LIMIT; index++) {
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
                 var query = connection.prepareStatement("EXPLAIN QUERY PLAN " + JdbcPublicationProtocol.LOOKUP_SQL)) {
                query.setString(1, "row"); query.setString(2, "masks"); query.setString(3, "key-4000");
                List<String> plan = new ArrayList<>();
                try (var result = query.executeQuery()) {
                    while (result.next()) { plan.add(result.getString("detail")); }
                }
                assertThat(plan).anyMatch(detail -> detail.contains("SEARCH f USING INDEX publication_fact_lookup"));
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

    private static int preparedUpdate(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) { return statement.executeUpdate(); }
    }

    private static final class Fixture implements AutoCloseable {
        private final HikariDataSource dataSource;
        private final JdbcWriterAdmission admission = new JdbcWriterAdmission();
        private final JdbcPublicationProtocol probe = new JdbcPublicationProtocol();

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

        JdbcPublicationProtocol.Receipt publish(Attempt attempt, long time, String value) {
            return write(OperationClass.PROMOTION, c -> probe.publish(c, attempt, time, value));
        }

        JdbcPublicationProtocol.Receipt publishWithValidity(Attempt attempt, long time, String value, Duration ttl) {
            return write(OperationClass.PROMOTION,
                    c -> probe.publish(c, attempt, time, value, new FixedRecordValidityPolicy(ttl)));
        }

        java.util.Optional<JdbcPublicationProtocol.Receipt> receipt(Attempt attempt) throws SQLException {
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
