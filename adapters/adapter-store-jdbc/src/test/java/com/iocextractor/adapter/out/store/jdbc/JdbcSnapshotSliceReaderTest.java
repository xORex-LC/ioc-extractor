package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSnapshotSliceReaderTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void streams_profile_in_plan_and_id_order_with_snapshot_metadata() {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "2", "mask", "b.example"),
                row("id", "1", "mask", "a.example"));
        fixture.write("hashes", row("id", "7", "hash", "BBBB"),
                row("id", "3", "hash", "AAAA"));
        RecordingConsumer consumer = new RecordingConsumer();

        SnapshotMetadata result = fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer);

        assertThat(consumer.events).containsExactly(
                "begin", "artifact:masks", "row:1", "row:2", "end-artifact",
                "artifact:hashes", "row:3", "row:7", "end-artifact", "end");
        assertThat(result).isEqualTo(consumer.metadata);
        assertThat(result.artifacts())
                .extracting(metadata -> metadata.artifactName())
                .containsExactly("masks", "hashes");
        assertThat(result.artifacts())
                .extracting(metadata -> metadata.coverage().revision())
                .containsExactly(1L, 1L);
        assertThat(result.artifacts())
                .extracting(metadata -> metadata.coverage().upperId())
                .containsExactly(2L, 7L);
        assertThat(result.artifacts())
                .allSatisfy(metadata -> {
                    assertThat(metadata.identityEpoch()).isOne();
                    assertThat(metadata.identityHash()).isNotBlank();
                    assertThat(metadata.schemaHash()).isEqualTo(HASH_B);
                });
    }

    @Test
    void commit_after_snapshot_start_is_deferred_to_the_next_snapshot() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        fixture.write("hashes", row("id", "1", "hash", "AAAA"));
        var began = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        BlockingConsumer consumer = new BlockingConsumer(began, release);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var current = executor.submit(() -> fixture.reader()
                    .stream(new SnapshotRequest(fixture.plan()), consumer));
            assertThat(began.await(5, TimeUnit.SECONDS)).isTrue();

            try {
                fixture.write("hashes", row("id", "2", "hash", "BBBB"));
            } finally {
                release.countDown();
            }
            SnapshotMetadata currentMetadata = current.get(5, TimeUnit.SECONDS);

            assertThat(consumer.rowsByArtifact.get("hashes")).containsExactly("1");
            assertThat(currentMetadata.artifacts().get(1).coverage().revision()).isEqualTo(1);
        }

        RecordingConsumer next = new RecordingConsumer();
        SnapshotMetadata nextMetadata = fixture.reader()
                .stream(new SnapshotRequest(fixture.plan()), next);
        assertThat(next.rowsByArtifact.get("hashes")).containsExactly("1", "2");
        assertThat(nextMetadata.artifacts().get(1).coverage().revision()).isEqualTo(2);
    }

    @Test
    void consumer_failure_is_propagated_and_all_jdbc_resources_are_released() {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        RuntimeException failure = new IllegalStateException("consumer failed");
        SnapshotRowConsumer consumer = new NoopConsumer() {
            @Override
            public void row(ArtifactRow row) {
                throw failure;
            }
        };

        assertThatThrownBy(() -> fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer))
                .isSameAs(failure);
        assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(dataSource.getHikariPoolMXBean().getIdleConnections()).isPositive();
    }

    @Test
    void unknown_artifact_is_rejected_before_snapshot_io() {
        Fixture fixture = fixture();
        ExportPlan unknown = plan(List.of(spec("unknown", "value", HASH_A)));

        assertThatThrownBy(() -> fixture.reader().stream(
                new SnapshotRequest(unknown), new NoopConsumer()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown dataframe artifact");
    }

    @Test
    void sql_failure_emits_snapshot_diagnostic() throws Exception {
        Fixture fixture = fixture();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE hashes");
        }
        var diagnostics = new CollectingDiagnosticSink();
        var reader = new JdbcSnapshotSliceReader(
                dataSource, fixture.schemas(), CLOCK, diagnostics, new DiagnosticFactory(CLOCK));

        assertThatThrownBy(() -> reader.stream(new SnapshotRequest(fixture.plan()), new NoopConsumer()))
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED.id());
        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED);
    }

    @Test
    void malformed_canonical_coverage_emits_snapshot_diagnostic() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE artifact_revision SET changed_at = ? WHERE artifact = ?")) {
            statement.setString(1, "not-an-instant");
            statement.setString(2, "masks");
            statement.executeUpdate();
        }
        var diagnostics = new CollectingDiagnosticSink();
        var reader = new JdbcSnapshotSliceReader(
                dataSource, fixture.schemas(), CLOCK, diagnostics, new DiagnosticFactory(CLOCK));

        assertThatThrownBy(() -> reader.stream(new SnapshotRequest(fixture.plan()), new NoopConsumer()))
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED.id())
                .hasRootCauseInstanceOf(java.time.format.DateTimeParseException.class);
        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED);
    }

    @Test
    void active_snapshot_uses_one_as_of_for_rows_and_coverage_across_artifacts() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks",
                row("id", "1", "mask", "active.example"),
                row("id", "2", "mask", "due.example"));
        fixture.write("hashes", row("id", "3", "hash", "AAAA"));
        lifecycle("masks", 1, 1, NOW.plusSeconds(1));
        lifecycle("masks", 2, 2, NOW);
        lifecycle("hashes", 3, 3, NOW.plusSeconds(1));
        var control = new JdbcLifecycleControlStore(dataSource, fixture.schemas());
        var activating = control.load().beginActivation("fixed-test-v1");
        assertThat(control.compareAndSet(control.load(), activating)).isTrue();

        assertThatThrownBy(() -> fixture.reader().stream(
                new SnapshotRequest(fixture.plan()), new NoopConsumer()))
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED.id())
                .hasRootCauseMessage("Canonical lifecycle activation is incomplete");

        assertThat(control.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();
        RecordingConsumer consumer = new RecordingConsumer();

        SnapshotMetadata result = fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer);

        assertThat(result.capturedAt()).isEqualTo(NOW);
        assertThat(consumer.rowsByArtifact.get("masks")).containsExactly("1");
        assertThat(consumer.rowsByArtifact.get("hashes")).containsExactly("3");
        assertThat(result.artifacts())
                .extracting(metadata -> metadata.coverage().upperId())
                .containsExactly(1L, 3L);
        assertThat(result.artifacts())
                .extracting(metadata -> metadata.coverage().revision())
                .containsExactly(1L, 1L);
    }

    @Test
    void preserves_survivors_and_reuses_smallest_expired_slots_without_compaction() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks",
                row("id", "1", "mask", "a.example"),
                row("id", "2", "mask", "b.example"),
                row("id", "3", "mask", "c.example"),
                row("id", "4", "mask", "d.example"),
                row("id", "5", "mask", "e.example"),
                row("id", "6", "mask", "f.example"));
        lifecycle("masks", 1, 101, NOW.plusSeconds(60));
        lifecycle("masks", 2, 102, NOW.plusSeconds(60));
        lifecycle("masks", 3, 103, NOW.plusSeconds(60));
        lifecycle("masks", 4, 204, NOW);
        lifecycle("masks", 5, 205, NOW);
        lifecycle("masks", 6, 206, NOW);
        activate(fixture);

        RecordingConsumer initial = new RecordingConsumer();
        fixture.reader().stream(new SnapshotRequest(fixture.plan()), initial);
        assertThat(initial.rowsByArtifact.get("masks")).containsExactly("1", "2", "3");

        lifecycle("masks", 1, 101, NOW);
        lifecycle("masks", 2, 102, NOW);
        lifecycle("masks", 4, 104, NOW.plusSeconds(60));

        RecordingConsumer afterExpiry = new RecordingConsumer();
        fixture.reader().stream(new SnapshotRequest(fixture.plan()), afterExpiry);
        assertThat(afterExpiry.valuesByArtifact.get("masks"))
                .extracting(values -> values.get("id"), values -> values.get("mask"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "d.example"),
                        org.assertj.core.groups.Tuple.tuple("3", "c.example"));

        lifecycle("masks", 5, 105, NOW.plusSeconds(60));
        RecordingConsumer filledHole = new RecordingConsumer();
        new JdbcSnapshotSliceReader(dataSource, fixture.schemas(), CLOCK)
                .stream(new SnapshotRequest(fixture.plan()), filledHole);

        assertThat(filledHole.valuesByArtifact.get("masks"))
                .extracting(values -> values.get("id"), values -> values.get("mask"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "d.example"),
                        org.assertj.core.groups.Tuple.tuple("2", "e.example"),
                        org.assertj.core.groups.Tuple.tuple("3", "c.example"));
        assertThat(slotForLifecycle("reputation", "masks", 103)).isEqualTo(3);
        assertThat(slotForLifecycle("reputation", "masks", 104)).isEqualTo(1);
        assertThat(slotForLifecycle("reputation", "masks", 105)).isEqualTo(2);

        lifecycle("masks", 6, 106, NOW.plusSeconds(60));
        RecordingConsumer aboveHighWater = new RecordingConsumer();
        fixture.reader().stream(new SnapshotRequest(fixture.plan()), aboveHighWater);

        assertThat(aboveHighWater.valuesByArtifact.get("masks"))
                .extracting(values -> values.get("id"), values -> values.get("mask"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "d.example"),
                        org.assertj.core.groups.Tuple.tuple("2", "e.example"),
                        org.assertj.core.groups.Tuple.tuple("3", "c.example"),
                        org.assertj.core.groups.Tuple.tuple("4", "f.example"));
        assertThat(queryLong("""
                SELECT next_slot FROM export_slot_state
                WHERE profile = 'reputation' AND artifact = 'masks'
                """)).isEqualTo(5);
    }

    @Test
    void seeds_sparse_current_ids_and_fills_the_smallest_upgrade_hole() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks",
                row("id", "1", "mask", "a.example"),
                row("id", "3", "mask", "c.example"),
                row("id", "4", "mask", "d.example"));
        lifecycle("masks", 1, 101, NOW.plusSeconds(60));
        lifecycle("masks", 3, 103, NOW.plusSeconds(60));
        lifecycle("masks", 4, 204, NOW);
        activate(fixture);

        fixture.reader().stream(new SnapshotRequest(fixture.plan()), new RecordingConsumer());
        lifecycle("masks", 4, 104, NOW.plusSeconds(60));
        RecordingConsumer consumer = new RecordingConsumer();

        fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer);

        assertThat(consumer.valuesByArtifact.get("masks"))
                .extracting(values -> values.get("id"), values -> values.get("mask"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "a.example"),
                        org.assertj.core.groups.Tuple.tuple("2", "d.example"),
                        org.assertj.core.groups.Tuple.tuple("3", "c.example"));
    }

    @Test
    void assigns_simultaneous_new_lifecycles_by_internal_lifecycle_order() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks",
                row("id", "1", "mask", "a.example"),
                row("id", "2", "mask", "b.example"),
                row("id", "3", "mask", "c.example"),
                row("id", "4", "mask", "later-lifecycle.example"),
                row("id", "5", "mask", "earlier-lifecycle.example"));
        lifecycle("masks", 1, 101, NOW.plusSeconds(60));
        lifecycle("masks", 2, 102, NOW.plusSeconds(60));
        lifecycle("masks", 3, 103, NOW.plusSeconds(60));
        lifecycle("masks", 4, 204, NOW);
        lifecycle("masks", 5, 205, NOW);
        activate(fixture);
        fixture.reader().stream(new SnapshotRequest(fixture.plan()), new RecordingConsumer());
        lifecycle("masks", 1, 101, NOW);
        lifecycle("masks", 2, 102, NOW);
        lifecycle("masks", 4, 110, NOW.plusSeconds(60));
        lifecycle("masks", 5, 109, NOW.plusSeconds(60));
        RecordingConsumer consumer = new RecordingConsumer();

        fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer);

        assertThat(consumer.valuesByArtifact.get("masks"))
                .extracting(values -> values.get("id"), values -> values.get("mask"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "earlier-lifecycle.example"),
                        org.assertj.core.groups.Tuple.tuple("2", "later-lifecycle.example"),
                        org.assertj.core.groups.Tuple.tuple("3", "c.example"));
    }

    @Test
    void leaves_artifacts_without_external_id_outside_slot_registry() throws Exception {
        List<DataframeArtifactSchema> schemas = List.of(schema("address_blacklist", "forbidden_url"));
        ArtifactIdentityDefinition identity = new ArtifactIdentityDefinition(
                "address_blacklist", List.of("forbidden_url"), false, 1);
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + tempDir.resolve("no-id.db"),
                        "low-memory", 1, 2));
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        new JdbcArtifactIdentityStore(dataSource, CLOCK).ensure(identity);
        var repository = new JdbcCanonicalArtifactRepository(
                dataSource, schemas,
                new CanonicalArtifactIdentityResolver(List.of(identity)), CLOCK);
        repository.write("address_blacklist", new CanonicalArtifact(
                "address_blacklist", List.of("forbidden_url"),
                List.of(row("forbidden_url", "https://example.test"))));
        lifecycle("address_blacklist", 1, 201, NOW.plusSeconds(60));
        var control = new JdbcLifecycleControlStore(dataSource, schemas);
        var activating = control.load().beginActivation("fixed-test-v1");
        assertThat(control.compareAndSet(control.load(), activating)).isTrue();
        assertThat(control.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();
        ExportPlan plan = plan(List.of(new ExportArtifactSpec(
                "address_blacklist", "address_blacklist.csv", List.of("forbidden_url"),
                1, identity.identityHash(), HASH_B, HASH_B)));
        RecordingConsumer consumer = new RecordingConsumer();

        new JdbcSnapshotSliceReader(dataSource, schemas, CLOCK)
                .stream(new SnapshotRequest(plan), consumer);

        assertThat(consumer.valuesByArtifact.get("address_blacklist")).hasSize(1);
        assertThat(consumer.valuesByArtifact.get("address_blacklist").getFirst())
                .containsEntry("forbidden_url", "https://example.test")
                .doesNotContainKey("id");
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_state")).isZero();
    }

    @Test
    void rejects_export_slot_policy_drift_before_streaming_rows() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        lifecycle("masks", 1, 101, NOW.plusSeconds(60));
        activate(fixture);
        fixture.reader().stream(new SnapshotRequest(fixture.plan()), new RecordingConsumer());
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE export_slot_state
                     SET policy_version = 'unsupported-v2'
                     WHERE profile = 'reputation' AND artifact = 'masks'
                     """)) {
            statement.executeUpdate();
        }

        assertThatThrownBy(() -> fixture.reader().stream(
                new SnapshotRequest(fixture.plan()), new RecordingConsumer()))
                .isInstanceOf(DiagnosticException.class)
                .hasRootCauseMessage(
                        "Export-slot policy mismatch for reputation/masks: expected "
                                + ExportPlan.EXPORT_SLOT_POLICY_VERSION
                                + ", found unsupported-v2");
    }

    @Test
    void rolls_back_registry_initialization_when_seed_is_invalid() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "0", "mask", "invalid.example"));
        lifecycle("masks", 0, 101, NOW.plusSeconds(60));
        activate(fixture);

        assertThatThrownBy(() -> fixture.reader().stream(
                new SnapshotRequest(fixture.plan()), new RecordingConsumer()))
                .isInstanceOf(DiagnosticException.class)
                .hasRootCauseMessage("Cannot seed positive export slots from active rows in masks");
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_state")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_assignment")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_free")).isZero();
    }

    @Test
    void detects_generation_change_before_any_snapshot_rows_are_exposed() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        lifecycle("masks", 1, 101, NOW.plusSeconds(60));
        activate(fixture);
        fixture.reader().stream(new SnapshotRequest(fixture.plan()), new RecordingConsumer());
        setProjectionGeneration("masks", 1);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var registry = new JdbcExportSlotRegistry();

            assertThatThrownBy(() -> registry.requireCurrentSnapshot(
                    connection, "reputation", "masks", NOW))
                    .isInstanceOf(JdbcExportSlotRegistry.SnapshotChangedException.class)
                    .hasMessageContaining("Canonical generation changed");
        }
    }

    @Test
    void concurrent_snapshot_readers_converge_on_one_slot_mapping() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks",
                row("id", "1", "mask", "a.example"),
                row("id", "2", "mask", "b.example"),
                row("id", "3", "mask", "c.example"));
        lifecycle("masks", 1, 101, NOW.plusSeconds(60));
        lifecycle("masks", 2, 102, NOW.plusSeconds(60));
        lifecycle("masks", 3, 103, NOW.plusSeconds(60));
        activate(fixture);
        var barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> streamAfterBarrier(fixture, barrier));
            var second = executor.submit(() -> streamAfterBarrier(fixture, barrier));

            assertThat(first.get(10, TimeUnit.SECONDS).rowsByArtifact.get("masks"))
                    .containsExactly("1", "2", "3");
            assertThat(second.get(10, TimeUnit.SECONDS).rowsByArtifact.get("masks"))
                    .containsExactly("1", "2", "3");
        }
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_assignment")).isEqualTo(3);
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_state")).isEqualTo(2);
    }

    @Test
    void reconciles_and_streams_a_100k_slot_reuse_wave_with_indexed_queries() throws Exception {
        Fixture fixture = fixture();
        bulkInsertMasks(1, 100_000, 1, NOW.plusSeconds(60));
        setProjectionGeneration("masks", 1);
        activate(fixture);
        CountingConsumer initial = new CountingConsumer();

        long initialStarted = System.nanoTime();
        SnapshotMetadata initialMetadata = fixture.reader()
                .stream(new SnapshotRequest(fixture.plan()), initial);
        long initialMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initialStarted);

        assertThat(initial.counts.get("masks")).isEqualTo(100_000);
        assertThat(initial.firstIds.get("masks")).isEqualTo("1");
        assertThat(initial.lastIds.get("masks")).isEqualTo("100000");
        assertThat(initialMetadata.artifacts().getFirst().coverage().upperId()).isEqualTo(100_000);
        assertThat(initialMillis).isLessThan(30_000);

        expireMaskIdsThrough(50_000);
        bulkInsertMasks(100_001, 150_000, 200_001, NOW.plusSeconds(60));
        setProjectionGeneration("masks", 2);
        CountingConsumer replacement = new CountingConsumer();

        long replacementStarted = System.nanoTime();
        SnapshotMetadata replacementMetadata = fixture.reader()
                .stream(new SnapshotRequest(fixture.plan()), replacement);
        long replacementMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - replacementStarted);

        assertThat(replacement.counts.get("masks")).isEqualTo(100_000);
        assertThat(replacement.firstIds.get("masks")).isEqualTo("1");
        assertThat(replacement.lastIds.get("masks")).isEqualTo("100000");
        assertThat(replacementMetadata.artifacts().getFirst().coverage().upperId()).isEqualTo(100_000);
        assertThat(slotForLifecycle("reputation", "masks", 200_001)).isEqualTo(1);
        assertThat(slotForLifecycle("reputation", "masks", 250_000)).isEqualTo(50_000);
        assertThat(slotForLifecycle("reputation", "masks", 50_001)).isEqualTo(50_001);
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_assignment")).isEqualTo(100_000);
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_free")).isZero();
        assertThat(replacementMillis).isLessThan(30_000);
        assertIndexedSlotQueryPlans();
    }

    private RecordingConsumer streamAfterBarrier(Fixture fixture, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        RecordingConsumer consumer = new RecordingConsumer();
        fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer);
        return consumer;
    }

    private void activate(Fixture fixture) {
        var control = new JdbcLifecycleControlStore(dataSource, fixture.schemas());
        var activating = control.load().beginActivation("fixed-test-v1");
        assertThat(control.compareAndSet(control.load(), activating)).isTrue();
        assertThat(control.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();
    }

    private void lifecycle(String artifact, long rowId, long lifecycleId, Instant validUntil)
            throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE " + artifact + " SET "
                     + "_lifecycle_id = ?, _first_confirmed_at_epoch_ms = ?, "
                     + "_last_confirmed_at_epoch_ms = ?, _valid_until_epoch_ms = ? WHERE id = ?")) {
            statement.setLong(1, lifecycleId);
            statement.setLong(2, NOW.minusSeconds(1).toEpochMilli());
            statement.setLong(3, NOW.minusSeconds(1).toEpochMilli());
            statement.setLong(4, validUntil.toEpochMilli());
            statement.setLong(5, rowId);
            statement.executeUpdate();
        }
    }

    private void bulkInsertMasks(long firstId,
                                 long lastId,
                                 long firstLifecycleId,
                                 Instant validUntil) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO masks(
                         id, mask, row_key, _created_at, _first_source_key,
                         _lifecycle_id, _first_confirmed_at_epoch_ms,
                         _last_confirmed_at_epoch_ms, _valid_until_epoch_ms)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            connection.setAutoCommit(false);
            int batchSize = 0;
            for (long id = firstId; id <= lastId; id++) {
                long lifecycleId = firstLifecycleId + id - firstId;
                statement.setLong(1, id);
                statement.setString(2, "load-" + id + ".example");
                statement.setString(3, "load-row-" + id);
                statement.setString(4, NOW.toString());
                statement.setString(5, "load-source");
                statement.setLong(6, lifecycleId);
                statement.setLong(7, NOW.minusSeconds(1).toEpochMilli());
                statement.setLong(8, NOW.minusSeconds(1).toEpochMilli());
                statement.setLong(9, validUntil.toEpochMilli());
                statement.addBatch();
                if (++batchSize == 1_000) {
                    statement.executeBatch();
                    batchSize = 0;
                }
            }
            if (batchSize > 0) {
                statement.executeBatch();
            }
            connection.commit();
        }
    }

    private void expireMaskIdsThrough(long lastId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE masks
                     SET _valid_until_epoch_ms = ?
                     WHERE id <= ?
                     """)) {
            statement.setLong(1, NOW.toEpochMilli());
            statement.setLong(2, lastId);
            statement.executeUpdate();
        }
    }

    private void setProjectionGeneration(String artifact, long generation) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO artifact_projection_state(
                         artifact, required_generation, projected_generation, requested_at_ms)
                     VALUES (?, ?, 0, ?)
                     ON CONFLICT(artifact) DO UPDATE SET
                         required_generation = excluded.required_generation,
                         requested_at_ms = excluded.requested_at_ms
                     """)) {
            statement.setString(1, artifact);
            statement.setLong(2, generation);
            statement.setLong(3, NOW.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void assertIndexedSlotQueryPlans() throws Exception {
        List<String> projectionPlan = explain("""
                SELECT a.slot AS id, t.mask AS mask
                FROM masks t
                JOIN export_slot_assignment a
                  ON a.profile = 'reputation'
                 AND a.artifact = 'masks'
                 AND a.lifecycle_id = t._lifecycle_id
                WHERE t._valid_until_epoch_ms > 0
                ORDER BY a.slot
                """);
        assertThat(projectionPlan)
                .anyMatch(line -> line.contains("ix_export_slot_assignment_slot"))
                .anyMatch(line -> line.contains("ux_masks_lifecycle_id"))
                .noneMatch(line -> line.contains("USE TEMP B-TREE FOR ORDER BY"));

        List<String> releasePlan = explain("""
                SELECT a.slot
                FROM export_slot_assignment a
                WHERE a.profile = 'reputation' AND a.artifact = 'masks'
                  AND NOT EXISTS (
                      SELECT 1 FROM masks t
                      WHERE t._lifecycle_id = a.lifecycle_id
                        AND t._valid_until_epoch_ms > 0)
                """);
        assertThat(releasePlan)
                .anyMatch(line -> line.contains("ix_export_slot_assignment_slot"))
                .anyMatch(line -> line.contains("ux_masks_lifecycle_id"));

        List<String> freePlan = explain("""
                SELECT slot
                FROM export_slot_free
                WHERE profile = 'reputation' AND artifact = 'masks'
                ORDER BY slot
                """);
        assertThat(freePlan)
                .anyMatch(line -> line.contains("sqlite_autoindex_export_slot_free_1"))
                .noneMatch(line -> line.contains("USE TEMP B-TREE FOR ORDER BY"));
    }

    private List<String> explain(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            List<String> plan = new ArrayList<>();
            while (resultSet.next()) {
                plan.add(resultSet.getString("detail"));
            }
            return plan;
        }
    }

    private long slotForLifecycle(String profile, String artifact, long lifecycleId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT slot
                     FROM export_slot_assignment
                     WHERE profile = ? AND artifact = ? AND lifecycle_id = ?
                     """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, lifecycleId);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private long queryLong(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private Fixture fixture() {
        List<DataframeArtifactSchema> schemas = List.of(
                schema("masks", "id", "mask"),
                schema("hashes", "id", "hash"));
        List<ArtifactIdentityDefinition> identities = List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1),
                new ArtifactIdentityDefinition("hashes", List.of("hash"), false, 1));
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + tempDir.resolve("snapshot.db"),
                        "low-memory", 1, 2));
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        var identityStore = new JdbcArtifactIdentityStore(dataSource, CLOCK);
        identities.forEach(identityStore::ensure);
        var repository = new JdbcCanonicalArtifactRepository(
                dataSource, schemas, new CanonicalArtifactIdentityResolver(identities), CLOCK);
        ExportPlan plan = plan(List.of(
                spec("masks", "mask", identities.get(0).identityHash()),
                spec("hashes", "hash", identities.get(1).identityHash())));
        return new Fixture(
                schemas,
                plan,
                repository,
                new JdbcSnapshotSliceReader(dataSource, schemas, CLOCK));
    }

    private ExportPlan plan(List<ExportArtifactSpec> artifacts) {
        List<String> names = artifacts.stream().map(artifact -> artifact.artifactName()).toList();
        return new ExportPlan(
                1,
                new ExportProfile("reputation", ExportMode.COMPLETE, names),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                artifacts);
    }

    private ExportArtifactSpec spec(String artifact, String valueColumn, String identityHash) {
        return new ExportArtifactSpec(
                artifact, artifact + ".csv", List.of("id", valueColumn),
                1, identityHash, HASH_B, HASH_B);
    }

    private DataframeArtifactSchema schema(String name, String... columns) {
        return new DataframeArtifactSchema(name, java.util.Arrays.stream(columns)
                .map(DataframeColumn::new)
                .toList());
    }

    private ArtifactRow row(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return ArtifactRow.ordered(values);
    }

    private static class RecordingConsumer implements SnapshotRowConsumer {
        final List<String> events = new ArrayList<>();
        final Map<String, List<String>> rowsByArtifact = new LinkedHashMap<>();
        final Map<String, List<Map<String, String>>> valuesByArtifact = new LinkedHashMap<>();
        SnapshotMetadata metadata;
        String artifact;

        @Override
        public void begin(SnapshotMetadata metadata) {
            this.metadata = metadata;
            events.add("begin");
        }

        @Override
        public void beginArtifact(SnapshotArtifactMetadata artifact) {
            this.artifact = artifact.artifactName();
            rowsByArtifact.put(this.artifact, new ArrayList<>());
            valuesByArtifact.put(this.artifact, new ArrayList<>());
            events.add("artifact:" + this.artifact);
        }

        @Override
        public void row(ArtifactRow row) {
            rowsByArtifact.get(artifact).add(row.value("id"));
            valuesByArtifact.get(artifact).add(row.values());
            events.add("row:" + row.value("id"));
        }

        @Override
        public void endArtifact() {
            events.add("end-artifact");
        }

        @Override
        public void end() {
            events.add("end");
        }
    }

    private static final class CountingConsumer extends NoopConsumer {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Map<String, String> firstIds = new LinkedHashMap<>();
        private final Map<String, String> lastIds = new LinkedHashMap<>();
        private String artifact;

        @Override
        public void beginArtifact(SnapshotArtifactMetadata artifact) {
            this.artifact = artifact.artifactName();
            counts.put(this.artifact, 0);
        }

        @Override
        public void row(ArtifactRow row) {
            String id = row.value("id");
            counts.compute(artifact, (ignored, count) -> count == null ? 1 : count + 1);
            firstIds.putIfAbsent(artifact, id);
            lastIds.put(artifact, id);
        }
    }

    private static final class BlockingConsumer extends RecordingConsumer {
        private final CountDownLatch began;
        private final CountDownLatch release;

        private BlockingConsumer(CountDownLatch began, CountDownLatch release) {
            this.began = began;
            this.release = release;
        }

        @Override
        public void begin(SnapshotMetadata metadata) {
            super.begin(metadata);
            began.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding snapshot", e);
            }
        }
    }

    private static class NoopConsumer implements SnapshotRowConsumer {
        @Override
        public void begin(SnapshotMetadata metadata) {
        }

        @Override
        public void beginArtifact(SnapshotArtifactMetadata artifact) {
        }

        @Override
        public void row(ArtifactRow row) {
        }

        @Override
        public void endArtifact() {
        }

        @Override
        public void end() {
        }
    }

    private record Fixture(List<DataframeArtifactSchema> schemas,
                           ExportPlan plan,
                           JdbcCanonicalArtifactRepository repository,
                           JdbcSnapshotSliceReader reader) {

        void write(String artifact, ArtifactRow... rows) {
            repository.write(artifact, new CanonicalArtifact(
                    artifact, rows[0].values().keySet().stream().toList(), List.of(rows)));
        }
    }
}
