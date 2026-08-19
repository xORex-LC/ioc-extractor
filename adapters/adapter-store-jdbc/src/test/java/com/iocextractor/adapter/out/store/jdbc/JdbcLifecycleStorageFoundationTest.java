package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdReservation;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleControlState;
import com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcLifecycleStorageFoundationTest {

    private static final Instant NOW = Instant.parse("2026-08-16T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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
    void upgrades_v3_legacy_rows_without_changing_disabled_business_data() throws Exception {
        dataSource = dataSource("legacy-v3.db", 1, 1);
        List<SqliteSchemaMigration> migrations = DataframeFormatMigrations.sqlite();
        new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 3)).migrate();
        execute("""
                CREATE TABLE masks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mask TEXT,
                    row_key TEXT NOT NULL UNIQUE,
                    _created_at TEXT NOT NULL,
                    _first_source_key TEXT
                )
                """);
        execute("""
                INSERT INTO masks(id, mask, row_key, _created_at, _first_source_key)
                VALUES (41, 'legacy.example', 'legacy-row', '2026-08-15T00:00:00Z', 'feed-a')
                """);

        SchemaMigrationResult migration = new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate();

        assertThat(migration.previousVersion()).isEqualTo(3);
        assertThat(migration.currentVersion()).isEqualTo(5);
        assertThat(migration.appliedVersions()).containsExactly(4, 5);
        assertThat(queryString("SELECT state FROM canonical_lifecycle_control WHERE singleton_id = 1"))
                .isEqualTo("DISABLED_COMPATIBLE");
        assertThat(queryLong("SELECT id FROM masks WHERE row_key = 'legacy-row'"))
                .isEqualTo(41);
        assertThat(queryString("SELECT mask FROM masks WHERE row_key = 'legacy-row'"))
                .isEqualTo("legacy.example");

        new DataframeSchemaReconciler(dataSource).reconcile(List.of(masksSchema()));

        assertThat(queryLong("""
                SELECT COUNT(*) FROM masks
                WHERE row_key = 'legacy-row'
                  AND _lifecycle_id IS NULL
                  AND _first_confirmed_at_epoch_ms IS NULL
                  AND _last_confirmed_at_epoch_ms IS NULL
                  AND _valid_until_epoch_ms IS NULL
                """)).isOne();
        var summary = new JdbcLifecycleMetadataInspector(dataSource).inspect("masks");
        assertThat(summary.totalRows()).isOne();
        assertThat(summary.legacyRows()).isOne();
        assertThat(summary.completeRows()).isZero();
        assertThat(summary.invalidRows()).isZero();
        assertThat(summary.activationReady()).isFalse();
    }

    @Test
    void lifecycle_control_allows_only_one_cas_winner_and_one_way_transitions() {
        initializeStatic("control.db");
        var firstStore = new JdbcLifecycleControlStore(dataSource, List.of());
        var competingStore = new JdbcLifecycleControlStore(dataSource, List.of());
        LifecycleControlState disabled = firstStore.load();
        LifecycleControlState activating = disabled.beginActivation("fixed-12h-v1");

        assertThat(firstStore.compareAndSet(disabled, activating)).isTrue();
        assertThat(competingStore.compareAndSet(disabled, activating)).isFalse();
        assertThat(competingStore.load()).isEqualTo(activating);

        LifecycleControlState active = activating.completeActivation(EffectiveTime.at(NOW));
        assertThat(competingStore.compareAndSet(activating, active)).isTrue();
        assertThat(firstStore.load()).isEqualTo(active);
        assertThatThrownBy(() -> firstStore.compareAndSet(active, activating))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void lifecycle_control_refuses_active_state_while_configured_rows_are_not_complete() throws Exception {
        initializeArtifact("activation-invariant.db", 1, 1);
        execute("""
                INSERT INTO masks(id, mask, row_key, _created_at)
                VALUES (1, 'legacy.example', 'legacy', '2026-08-16T00:00:00Z')
                """);
        var store = new JdbcLifecycleControlStore(dataSource, List.of(masksSchema()));
        LifecycleControlState activating = store.load().beginActivation("fixed-12h-v1");
        assertThat(store.compareAndSet(store.load(), activating)).isTrue();
        LifecycleControlState active = activating.completeActivation(EffectiveTime.at(NOW));

        assertThatThrownBy(() -> store.compareAndSet(activating, active))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("activation-ready");
        assertThat(store.load()).isEqualTo(activating);

        execute("""
                UPDATE masks
                SET _lifecycle_id = 1,
                    _first_confirmed_at_epoch_ms = 10,
                    _last_confirmed_at_epoch_ms = 20,
                    _valid_until_epoch_ms = 30
                WHERE row_key = 'legacy'
                """);
        assertThat(store.compareAndSet(activating, active)).isTrue();
        assertThat(store.load()).isEqualTo(active);
    }

    @Test
    void concurrent_activation_completion_has_one_transactional_cas_winner() throws Exception {
        initializeArtifact("activation-race.db", 2, 2);
        var schemas = List.of(masksSchema());
        var setupStore = new JdbcLifecycleControlStore(dataSource, schemas);
        LifecycleControlState activating = setupStore.load().beginActivation("fixed-12h-v1");
        assertThat(setupStore.compareAndSet(setupStore.load(), activating)).isTrue();
        LifecycleControlState active = activating.completeActivation(EffectiveTime.at(NOW));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Activation CAS start barrier timed out");
                    }
                    return new JdbcLifecycleControlStore(dataSource, schemas)
                            .compareAndSet(activating, active);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS))
                    .isNotEqualTo(futures.get(1).get(10, TimeUnit.SECONDS));
        }
        assertThat(setupStore.load()).isEqualTo(active);
    }

    @Test
    void projection_acknowledgement_is_generation_cas() throws Exception {
        initializeStatic("projection.db");
        execute("""
                INSERT INTO artifact_projection_state(
                    artifact, required_generation, projected_generation, requested_at_ms)
                VALUES ('masks', 2, 0, 100)
                """);
        var store = new JdbcArtifactProjectionWorkStore(dataSource, CLOCK);

        assertThat(store.load("masks").pending()).isTrue();
        assertThat(store.acknowledge(acknowledgement("masks", 2))).isTrue();
        assertThat(store.load("masks").pending()).isFalse();
        assertThat(queryLong("SELECT projected_at_ms FROM artifact_projection_state WHERE artifact = 'masks'"))
                .isEqualTo(NOW.toEpochMilli());

        execute("""
                UPDATE artifact_projection_state
                SET required_generation = 3, requested_at_ms = 200
                WHERE artifact = 'masks'
                """);
        assertThat(store.acknowledge(acknowledgement("masks", 2))).isFalse();
        assertThat(store.load("masks").requiredGeneration()).isEqualTo(new ProjectionGeneration(3));
        assertThat(store.load("masks").projectedGeneration()).isEqualTo(new ProjectionGeneration(2));
        assertThat(store.load("hashes").pending()).isFalse();
    }

    @Test
    void receipt_and_observation_headers_enforce_complete_state_shape_and_cascade() throws Exception {
        initializeStatic("durable-markers.db");
        execute("""
                INSERT INTO confirmation_receipt(
                    receipt_id, source_key, processing_policy_fingerprint,
                    state, expected_artifacts, row_count)
                VALUES ('receipt-1', 'source-a', 'policy-a', 'STAGING', 1, 2)
                """);
        execute("""
                INSERT INTO confirmation_receipt_artifact(receipt_id, artifact, row_count, staged_at_ms)
                VALUES ('receipt-1', 'masks', 2, 100)
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE confirmation_receipt
                SET state = 'COMPLETE', completed_at_ms = 200
                WHERE receipt_id = 'receipt-1'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK constraint failed");
        execute("""
                UPDATE confirmation_receipt
                SET state = 'COMPLETE', completed_at_ms = 200, purge_after_ms = 300
                WHERE receipt_id = 'receipt-1'
                """);

        execute("""
                INSERT INTO canonical_observation(
                    observation_id, source_key, state, started_at_ms)
                VALUES ('observation-1', 'source-a', 'OPEN', 100)
                """);
        execute("""
                INSERT INTO canonical_observation_commit(
                    observation_id, artifact, committed_at_ms, effective_as_of_ms,
                    inserted, renewed, restarted, artifact_revision, projection_generation)
                VALUES ('observation-1', 'masks', 150, 140, 1, 0, 0, 1, 1)
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE canonical_observation
                SET state = 'TERMINAL', terminal_at_ms = 200
                WHERE observation_id = 'observation-1'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK constraint failed");
        execute("""
                UPDATE canonical_observation
                SET state = 'TERMINAL', terminal_at_ms = 200, purge_after_ms = 300
                WHERE observation_id = 'observation-1'
                """);

        execute("DELETE FROM confirmation_receipt WHERE receipt_id = 'receipt-1'");
        execute("DELETE FROM canonical_observation WHERE observation_id = 'observation-1'");
        assertThat(queryLong("SELECT COUNT(*) FROM confirmation_receipt_artifact")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM canonical_observation_commit")).isZero();
    }

    @Test
    void public_allocator_seeds_from_active_and_history_then_survives_their_deletion() throws Exception {
        initializeArtifact("public-ids.db", 1, 1);
        execute("""
                INSERT INTO masks(id, mask, row_key, _created_at)
                VALUES (40, 'active.example', 'active-row', '2026-08-16T00:00:00Z')
                """);
        execute("""
                INSERT INTO masks_history(
                    former_row_id, row_key, _lifecycle_id,
                    _first_confirmed_at_epoch_ms, _last_confirmed_at_epoch_ms,
                    _valid_until_epoch_ms, closed_at_epoch_ms, close_reason, id, mask)
                VALUES (75, 'history-row', 900, 10, 20, 30, 30, 'EXPIRED', 75, 'old.example')
                """);
        var definition = new ArtifactIdAllocatorDefinition(
                "masks", ArtifactIdStrategy.ASCENDING, 10, 1);
        var allocator = new JdbcArtifactIdAllocator(dataSource, CLOCK);

        allocator.ensureInitialized(definition);
        var first = allocator.reserve("masks", 2);
        assertThat(first.start()).isEqualTo(76);
        assertThat(first.idAt(1)).isEqualTo(77);

        execute("DELETE FROM masks");
        execute("DELETE FROM masks_history");
        var restarted = new JdbcArtifactIdAllocator(dataSource, CLOCK);
        restarted.ensureInitialized(definition);
        assertThat(restarted.reserve("masks", 1).start()).isEqualTo(78);
        restarted.ensureInitialized(new ArtifactIdAllocatorDefinition(
                "masks", ArtifactIdStrategy.ASCENDING, 10, 2));
        assertThat(restarted.reserve("masks", 1).start()).isEqualTo(79);
        assertThatThrownBy(() -> restarted.ensureInitialized(definition))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("newer than configured");
        assertThatThrownBy(() -> restarted.ensureInitialized(new ArtifactIdAllocatorDefinition(
                "masks", ArtifactIdStrategy.DESCENDING, -1, 1)))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("identity drift");
    }

    @Test
    void descending_public_allocator_uses_the_lowest_stored_id() throws Exception {
        initializeArtifact("descending-ids.db", 1, 1);
        execute("""
                INSERT INTO masks(id, mask, row_key, _created_at)
                VALUES (-20, 'active.example', 'active-row', '2026-08-16T00:00:00Z')
                """);
        execute("""
                INSERT INTO masks_history(
                    former_row_id, row_key, _lifecycle_id,
                    _first_confirmed_at_epoch_ms, _last_confirmed_at_epoch_ms,
                    _valid_until_epoch_ms, closed_at_epoch_ms, close_reason, id, mask)
                VALUES (-30, 'history-row', 901, 10, 20, 30, 30, 'EXPIRED', -30, 'old.example')
                """);
        var allocator = new JdbcArtifactIdAllocator(dataSource, CLOCK);
        allocator.ensureInitialized(new ArtifactIdAllocatorDefinition(
                "masks", ArtifactIdStrategy.DESCENDING, -10, 1));

        var reservation = allocator.reserve("masks", 3);

        assertThat(reservation.start()).isEqualTo(-31);
        assertThat(reservation.idAt(2)).isEqualTo(-33);
    }

    @Test
    void public_allocator_reservations_are_atomic_across_concurrent_clients() throws Exception {
        initializeArtifact("concurrent-public-ids.db", 4, 4);
        var definition = new ArtifactIdAllocatorDefinition(
                "masks", ArtifactIdStrategy.ASCENDING, 100, 1);
        new JdbcArtifactIdAllocator(dataSource, CLOCK).ensureInitialized(definition);

        int workers = 8;
        int rangeSize = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ArtifactIdReservation>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent allocator start barrier timed out");
                    }
                    return new JdbcArtifactIdAllocator(dataSource, CLOCK).reserve("masks", rangeSize);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<Long> allocated = new HashSet<>();
            for (Future<ArtifactIdReservation> future : futures) {
                var reservation = future.get(10, TimeUnit.SECONDS);
                for (int offset = 0; offset < reservation.count(); offset++) {
                    assertThat(allocated.add(reservation.idAt(offset))).isTrue();
                }
            }
            assertThat(allocated).containsExactlyInAnyOrderElementsOf(longRange(100, 131));
        }
        assertThat(queryLong("SELECT next_value FROM artifact_id_allocator WHERE artifact = 'masks'"))
                .isEqualTo(132);
    }

    @Test
    void lifecycle_allocator_reservations_are_atomic_across_concurrent_clients() throws Exception {
        initializeStatic("lifecycle-ids.db", 4, 4);
        var firstClient = new JdbcLifecycleIdAllocator(dataSource, CLOCK);
        assertThat(firstClient.reserve(3).start()).isOne();
        assertThat(new JdbcLifecycleIdAllocator(dataSource, CLOCK).reserve(2).start()).isEqualTo(4);

        int workers = 8;
        int rangeSize = 5;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LifecycleIdReservation>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent allocator start barrier timed out");
                    }
                    return new JdbcLifecycleIdAllocator(dataSource, CLOCK).reserve(rangeSize);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<Long> allocated = new HashSet<>();
            for (Future<LifecycleIdReservation> future : futures) {
                LifecycleIdReservation reservation = future.get(10, TimeUnit.SECONDS);
                for (int offset = 0; offset < reservation.count(); offset++) {
                    assertThat(allocated.add(reservation.idAt(offset).value())).isTrue();
                }
            }
            assertThat(allocated).containsExactlyInAnyOrderElementsOf(longRange(6, 45));
        }
        assertThat(queryLong("SELECT next_value FROM lifecycle_id_allocator WHERE singleton_id = 1"))
                .isEqualTo(46);
    }

    @Test
    void reserved_ranges_remain_consumed_when_the_calling_transaction_rolls_back() {
        initializeArtifact("allocator-rollback.db", 2, 2);
        var publicIds = new JdbcArtifactIdAllocator(dataSource, CLOCK);
        publicIds.ensureInitialized(new ArtifactIdAllocatorDefinition(
                "masks", ArtifactIdStrategy.ASCENDING, 100, 1));
        var lifecycleIds = new JdbcLifecycleIdAllocator(dataSource, CLOCK);
        var outer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            publicIds.reserve("masks", 2);
            lifecycleIds.reserve(3);
            throw new IllegalStateException("canonical write failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(publicIds.reserve("masks", 1).start()).isEqualTo(102);
        assertThat(lifecycleIds.reserve(1).start()).isEqualTo(4);
    }

    @Test
    void metadata_inspector_distinguishes_legacy_complete_and_invalid_rows() throws Exception {
        initializeArtifact("metadata.db", 1, 1);
        execute("""
                INSERT INTO masks(id, mask, row_key, _created_at)
                VALUES (1, 'legacy.example', 'legacy', '2026-08-16T00:00:00Z')
                """);
        execute("""
                INSERT INTO masks(
                    id, mask, row_key, _created_at, _lifecycle_id,
                    _first_confirmed_at_epoch_ms, _last_confirmed_at_epoch_ms,
                    _valid_until_epoch_ms)
                VALUES (2, 'complete.example', 'complete', '2026-08-16T00:00:00Z', 2, 10, 20, 30)
                """);
        execute("""
                INSERT INTO masks(
                    id, mask, row_key, _created_at, _lifecycle_id,
                    _first_confirmed_at_epoch_ms, _last_confirmed_at_epoch_ms,
                    _valid_until_epoch_ms)
                VALUES (3, 'partial.example', 'partial', '2026-08-16T00:00:00Z', 3, 10, NULL, 30)
                """);
        var inspector = new JdbcLifecycleMetadataInspector(dataSource);

        var summary = inspector.inspect("masks");

        assertThat(summary.totalRows()).isEqualTo(3);
        assertThat(summary.legacyRows()).isOne();
        assertThat(summary.completeRows()).isOne();
        assertThat(summary.invalidRows()).isOne();
        assertThat(summary.activationReady()).isFalse();
        assertThatThrownBy(() -> inspector.requireActivationReady("masks"))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("legacy=1, invalid=1");

        execute("DELETE FROM masks WHERE row_key IN ('legacy', 'partial')");
        assertThat(inspector.inspect("masks").activationReady()).isTrue();
    }

    @Test
    void legacy_activation_resumes_in_batches_and_preserves_revision_and_provenance() throws Exception {
        initializeArtifact("legacy-activation.db", 1, 1);
        execute("""
                INSERT INTO artifact_revision(artifact, revision, changed_at)
                VALUES ('masks', 9, '2026-08-15T00:00:00Z')
                """);
        for (int id = 1; id <= 3; id++) {
            execute("""
                    INSERT INTO masks(id, mask, row_key, _created_at, _first_source_key)
                    VALUES (%1$d, 'legacy-%1$d.example', 'legacy-%1$d',
                            '2026-08-15T00:00:00Z', 'feed-a')
                    """.formatted(id));
            execute("""
                    INSERT INTO masks_sources(
                        row_id, source_key, first_seen_at, last_seen_at, occurrences)
                    VALUES (%d, 'feed-a', '2026-08-15T00:00:00Z',
                            '2026-08-15T01:00:00Z', 2)
                    """.formatted(id));
        }
        var control = new JdbcLifecycleControlStore(dataSource, List.of(masksSchema()));
        LifecycleControlState disabled = control.load();
        assertThat(control.compareAndSet(
                disabled, disabled.beginActivation("record-validity:fixed:v1"))).isTrue();

        var firstProcess = new JdbcLifecycleActivationStore(
                dataSource, List.of(masksSchema()), CLOCK);
        var firstBatch = firstProcess.expireLegacyBatch(
                "masks", EffectiveTime.at(NOW), 2);

        assertThat(firstBatch.expired()).isEqualTo(2);
        assertThat(firstBatch.moreLegacyRows()).isTrue();
        assertThat(queryLong("SELECT COUNT(*) FROM masks")).isOne();

        var restartedProcess = new JdbcLifecycleActivationStore(
                dataSource, List.of(masksSchema()), CLOCK);
        var finalBatch = restartedProcess.expireLegacyBatch(
                "masks", EffectiveTime.at(NOW), 2);

        assertThat(finalBatch.expired()).isOne();
        assertThat(finalBatch.moreLegacyRows()).isFalse();
        assertThat(restartedProcess.expireLegacyBatch(
                "masks", EffectiveTime.at(NOW), 2).expired()).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM masks")).isZero();
        assertThat(queryLong("""
                SELECT COUNT(*) FROM masks_history
                WHERE close_reason = 'LEGACY_ACTIVATION'
                  AND _first_confirmed_at_epoch_ms < _valid_until_epoch_ms
                  AND _last_confirmed_at_epoch_ms < _valid_until_epoch_ms
                """)).isEqualTo(3);
        assertThat(queryLong("SELECT COUNT(*) FROM masks_history_sources")).isEqualTo(3);
        assertThat(queryLong("SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isEqualTo(9);
        assertThat(queryLong("""
                SELECT required_generation FROM artifact_projection_state
                WHERE artifact = 'masks'
                """)).isOne();
        assertThat(queryLong("""
                SELECT expired_count FROM lifecycle_activation_progress
                WHERE artifact = 'masks' AND completed = 1
                """)).isEqualTo(3);
    }

    @Test
    void resumed_activation_rejects_partially_populated_lifecycle_metadata() throws Exception {
        initializeArtifact("invalid-activation.db", 1, 1);
        execute("""
                INSERT INTO masks(
                    id, mask, row_key, _created_at, _lifecycle_id,
                    _first_confirmed_at_epoch_ms, _last_confirmed_at_epoch_ms,
                    _valid_until_epoch_ms)
                VALUES (1, 'partial.example', 'partial', '2026-08-16T00:00:00Z',
                        1, 10, NULL, 30)
                """);
        var control = new JdbcLifecycleControlStore(dataSource, List.of(masksSchema()));
        LifecycleControlState disabled = control.load();
        assertThat(control.compareAndSet(
                disabled, disabled.beginActivation("record-validity:fixed:v1"))).isTrue();

        assertThatThrownBy(() -> new JdbcLifecycleActivationStore(
                dataSource, List.of(masksSchema()), CLOCK).expireLegacyBatch(
                "masks", EffectiveTime.at(NOW), 10))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("partially populated");
    }

    @Test
    void lifecycle_cleanup_queries_use_deadline_and_retention_indexes() throws Exception {
        initializeArtifact("query-plan.db", 1, 1);

        assertThat(queryPlan("""
                EXPLAIN QUERY PLAN
                SELECT _lifecycle_id
                FROM masks
                WHERE _valid_until_epoch_ms <= 1000
                ORDER BY _valid_until_epoch_ms, _lifecycle_id
                LIMIT 100
                """)).contains("ix_masks_lifecycle_due");
        assertThat(queryPlan("""
                EXPLAIN QUERY PLAN
                SELECT COUNT(*)
                FROM masks
                WHERE _valid_until_epoch_ms <= 1000
                """)).contains("ix_masks_lifecycle_due");
        assertThat(queryPlan("""
                EXPLAIN QUERY PLAN
                SELECT history_id
                FROM masks_history
                WHERE closed_at_epoch_ms <= 1000
                ORDER BY closed_at_epoch_ms, history_id
                LIMIT 100
                """)).contains("ix_masks_history_retention");
        assertThat(queryPlan("""
                EXPLAIN QUERY PLAN
                SELECT receipt_id
                FROM confirmation_receipt
                WHERE state = 'COMPLETE' AND purge_after_ms <= 1000
                ORDER BY purge_after_ms, receipt_id
                LIMIT 100
                """)).contains("ix_confirmation_receipt_retention");
        assertThat(queryPlan("""
                EXPLAIN QUERY PLAN
                SELECT observation_id
                FROM canonical_observation
                WHERE state = 'TERMINAL' AND purge_after_ms <= 1000
                ORDER BY purge_after_ms, observation_id
                LIMIT 100
                """)).contains("ix_canonical_observation_retention");
    }

    private ProjectionAcknowledgement acknowledgement(String artifact, long generation) {
        var value = new ProjectionGeneration(generation);
        return new ProjectionAcknowledgement(artifact, value, value);
    }

    private void initializeStatic(String fileName) {
        initializeStatic(fileName, 1, 1);
    }

    private void initializeStatic(String fileName, int writeMax, int readMax) {
        dataSource = dataSource(fileName, writeMax, readMax);
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
    }

    private void initializeArtifact(String fileName, int writeMax, int readMax) {
        initializeStatic(fileName, writeMax, readMax);
        new DataframeSchemaReconciler(dataSource).reconcile(List.of(masksSchema()));
    }

    private DataframeArtifactSchema masksSchema() {
        return new DataframeArtifactSchema("masks", List.of(
                new DataframeColumn("id", "INTEGER"),
                new DataframeColumn("mask", "TEXT")));
    }

    private HikariDataSource dataSource(String fileName, int writeMax, int readMax) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + db, "low-memory", writeMax, readMax));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private String queryPlan(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            StringBuilder details = new StringBuilder();
            while (resultSet.next()) {
                details.append(resultSet.getString("detail")).append('\n');
            }
            return details.toString();
        }
    }

    private List<Long> longRange(long start, long endInclusive) {
        List<Long> values = new ArrayList<>();
        for (long value = start; value <= endInclusive; value++) {
            values.add(value);
        }
        return values;
    }
}
