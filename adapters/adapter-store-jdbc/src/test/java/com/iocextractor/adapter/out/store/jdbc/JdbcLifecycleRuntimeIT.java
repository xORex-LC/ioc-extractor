package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockStatus;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockUnsafeException;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JdbcLifecycleRuntimeIT {

    private static final Instant NOW = Instant.parse("2026-08-16T02:00:00Z");

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
    void safe_clock_advances_high_water_clamps_small_rollback_and_recovers() throws Exception {
        initialize("clock.db");
        MutableClock wall = new MutableClock(NOW);
        AtomicLong monotonic = new AtomicLong();
        var clock = new JdbcLifecycleClock(dataSource, wall,
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)),
                monotonic::get);

        assertThat(clock.now().value()).isEqualTo(NOW);
        wall.set(NOW.minusSeconds(1));

        assertThat(clock.now().value()).isEqualTo(NOW);
        assertThat(clock.inspect().status()).isEqualTo(LifecycleClockStatus.CLAMPED);
        assertThat(queryLong("SELECT safe_time_high_water_ms FROM canonical_lifecycle_control"))
                .isEqualTo(NOW.toEpochMilli());
        assertThat(queryLong("SELECT clamp_started_at_ms FROM canonical_lifecycle_control"))
                .isEqualTo(NOW.minusSeconds(1).toEpochMilli());

        wall.set(NOW.plusSeconds(1));
        assertThat(clock.now().value()).isEqualTo(NOW.plusSeconds(1));
        assertThat(clock.inspect().status()).isEqualTo(LifecycleClockStatus.SAFE);
        assertThat(queryNullableLong("SELECT clamp_started_at_ms FROM canonical_lifecycle_control"))
                .isNull();
    }

    @Test
    void safe_clock_fails_closed_for_material_or_prolonged_rollback() throws Exception {
        initialize("unsafe-clock.db");
        MutableClock wall = new MutableClock(NOW);
        AtomicLong monotonic = new AtomicLong();
        var clock = new JdbcLifecycleClock(dataSource, wall,
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)),
                monotonic::get);
        clock.now();

        wall.set(NOW.minusSeconds(3));
        assertThatThrownBy(clock::now).isInstanceOf(LifecycleClockUnsafeException.class);
        assertThat(queryLong("SELECT safe_time_high_water_ms FROM canonical_lifecycle_control"))
                .isEqualTo(NOW.toEpochMilli());

        wall.set(NOW.minusSeconds(1));
        clock.now();
        monotonic.set(Duration.ofSeconds(31).toNanos());
        assertThatThrownBy(clock::now).isInstanceOf(LifecycleClockUnsafeException.class);
        assertThat(clock.inspect().status()).isEqualTo(LifecycleClockStatus.UNSAFE);
    }

    @Test
    void history_retention_is_bounded_and_cascades_compact_sources() throws Exception {
        initialize("retention.db");
        insertHistory(1, 100);
        insertHistory(2, 200);
        var history = new JdbcLifecycleHistoryStore(dataSource, List.of(masksSchema()));

        var first = history.purge("masks", EffectiveTime.at(Instant.ofEpochMilli(1_000)), 1);

        assertThat(first.purged()).isOne();
        assertThat(first.moreEligible()).isTrue();
        assertThat(queryLong("SELECT COUNT(*) FROM masks_history")).isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM masks_history_sources")).isOne();
        assertThat(history.purge("masks", EffectiveTime.at(Instant.ofEpochMilli(1_000)), 1).purged())
                .isOne();
    }

    @Test
    void reconciliation_checkpoint_recovers_interrupted_cycle_and_keeps_constant_cardinality() throws Exception {
        initialize("cycles.db");
        var store = new JdbcLifecycleReconciliationStore(dataSource);
        var interrupted = store.start(EffectiveTime.at(NOW));
        store.recordBatch(interrupted, 4);

        assertThat(store.failInterrupted(EffectiveTime.at(NOW.plusSeconds(1)),
                "LIFECYCLE.RECONCILIATION_INTERRUPTED")).isOne();
        assertThat(queryString("SELECT state FROM lifecycle_reconcile_state WHERE singleton_id = 1"))
                .isEqualTo("FAILED");

        var completed = store.start(EffectiveTime.at(NOW.plusSeconds(2)));
        store.recordBatch(completed, 7);
        store.complete(completed, EffectiveTime.at(NOW.plusSeconds(3)), 7, 1);
        assertThat(completed.value()).isEqualTo(2);
        assertThat(queryString("SELECT state FROM lifecycle_reconcile_state WHERE singleton_id = 1"))
                .isEqualTo("COMPLETED");
        assertThat(queryLong("SELECT expired_count FROM lifecycle_reconcile_state WHERE singleton_id = 1"))
                .isEqualTo(7);
        assertThat(queryLong("""
                SELECT affected_artifact_count FROM lifecycle_reconcile_state WHERE singleton_id = 1
                """))
                .isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM lifecycle_reconcile_state")).isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM lifecycle_reconcile_cycle")).isZero();
    }

    @Test
    void status_is_read_only_and_projection_failure_is_generation_guarded() throws Exception {
        initialize("status.db");
        MutableClock wall = new MutableClock(NOW);
        var clock = new JdbcLifecycleClock(dataSource, wall,
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)));
        clock.now();
        execute("""
                INSERT INTO artifact_projection_state(
                    artifact, required_generation, projected_generation, requested_at_ms)
                VALUES ('masks', 2, 1, 100)
                """);
        var projections = new JdbcArtifactProjectionWorkStore(dataSource, wall);
        assertThat(projections.recordFailure("masks", new ProjectionGeneration(2),
                "LIFECYCLE.PROJECTION_FAILED")).isTrue();
        assertThat(projections.recordFailure("masks", new ProjectionGeneration(1),
                "LIFECYCLE.PROJECTION_FAILED")).isFalse();
        Long beforeHighWater = queryNullableLong(
                "SELECT safe_time_high_water_ms FROM canonical_lifecycle_control");
        Long beforeClamp = queryNullableLong(
                "SELECT clamp_started_at_ms FROM canonical_lifecycle_control");

        var status = new JdbcLifecycleStatusReader(dataSource, List.of(masksSchema()), clock).read();

        assertThat(status.pendingProjections()).isOne();
        assertThat(status.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.artifactName()).isEqualTo("masks");
            assertThat(artifact.stored()).isZero();
            assertThat(artifact.history()).isZero();
        });
        assertThat(queryNullableLong("SELECT safe_time_high_water_ms FROM canonical_lifecycle_control"))
                .isEqualTo(beforeHighWater);
        assertThat(queryNullableLong("SELECT clamp_started_at_ms FROM canonical_lifecycle_control"))
                .isEqualTo(beforeClamp);
    }

    private void initialize(String fileName) {
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe",
                        "jdbc:sqlite:" + tempDir.resolve(fileName), "low-memory", 1, 1));
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(List.of(masksSchema()));
    }

    private DataframeArtifactSchema masksSchema() {
        return new DataframeArtifactSchema("masks", List.of(
                new DataframeColumn("id", "INTEGER"),
                new DataframeColumn("mask", "TEXT")));
    }

    private void insertHistory(long lifecycleId, long closedAt) throws SQLException {
        execute("""
                INSERT INTO masks_history(
                    former_row_id, row_key, _lifecycle_id,
                    _first_confirmed_at_epoch_ms, _last_confirmed_at_epoch_ms,
                    _valid_until_epoch_ms, closed_at_epoch_ms, close_reason, id, mask)
                VALUES (%1$d, 'row-%1$d', %1$d, 10, 20, 30, %2$d, 'EXPIRED', %1$d, 'example')
                """.formatted(lifecycleId, closedAt));
        execute("""
                INSERT INTO masks_history_sources(
                    history_id, source_key, first_seen_at, last_seen_at, occurrences)
                VALUES (%1$d, 'source-%1$d', '2026-08-16T00:00:00Z',
                        '2026-08-16T00:00:00Z', 1)
                """.formatted(lifecycleId));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        return queryNullableLong(sql);
    }

    private Long queryNullableLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            long value = resultSet.getLong(1);
            return resultSet.wasNull() ? null : value;
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

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
