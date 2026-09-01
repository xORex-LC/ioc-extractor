package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JdbcExportSlotRegistryIT {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant ACTIVE_UNTIL = NOW.plusSeconds(60);
    private static final String PROFILE = "reputation";
    private static final String ARTIFACT = "masks";
    private static final long SPARSE_SLOT = 1_000_000_000L;
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private JdbcExportSlotRegistry registry;

    @BeforeEach
    void setUp() {
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + tempDir.resolve("slots.db"),
                        "low-memory", 1, 1));
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(List.of(
                new DataframeArtifactSchema(ARTIFACT, List.of(
                        new DataframeColumn("id"), new DataframeColumn("mask")))));
        registry = new JdbcExportSlotRegistry();
    }

    @AfterEach
    void close() {
        dataSource.close();
    }

    @Test
    void assigns_extreme_sparse_request_exactly_and_uses_one_bounded_range() throws Exception {
        addActive(1, 101);
        addActive(2, 102);

        long started = System.nanoTime();
        List<PreferredExportSlotResolution> exact = preferred(request(102, SPARSE_SLOT, true));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(exact).containsExactly(new PreferredExportSlotResolution(
                102, SPARSE_SLOT, SPARSE_SLOT,
                PreferredExportSlotResolution.Outcome.EXACT));
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_free_range")).isOne();
        assertThat(ranges()).isEqualTo("2-999999999");
        assertThat(queryLong("SELECT next_slot FROM export_slot_state")).isEqualTo(1_000_000_001L);
        assertThat(explain("""
                SELECT range_start, range_end
                FROM export_slot_free_range
                WHERE profile = 'reputation' AND artifact = 'masks'
                  AND range_start <= 1000000000 AND range_end >= 1000000000
                ORDER BY range_start DESC
                LIMIT 1
                """))
                .anyMatch(line -> line.contains("SEARCH export_slot_free_range USING INDEX"))
                .noneMatch(line -> line.contains("SCAN export_slot_free_range"));

        addActive(3, 103);
        assertThat(preferred(request(103, SPARSE_SLOT, true)))
                .extracting(PreferredExportSlotResolution::assignedSlot,
                        PreferredExportSlotResolution::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        2L, PreferredExportSlotResolution.Outcome.OCCUPIED_FALLBACK));

        addActive(4, 104);
        assertThat(preferred(request(104, 500_000_000L, true)))
                .extracting(PreferredExportSlotResolution::assignedSlot,
                        PreferredExportSlotResolution::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        500_000_000L, PreferredExportSlotResolution.Outcome.EXACT));
        assertThat(ranges()).isEqualTo("3-499999999,500000001-999999999");
        assertThat(assignments()).isEqualTo("101:1,102:1000000000,103:2,104:500000000");
    }

    @Test
    void preserves_or_rejects_a_survivor_slot_mismatch_by_declared_policy() throws Exception {
        addActive(1, 101);
        reconcileOrdinary();

        assertThat(preferred(new PreferredExportSlotRequest(
                101, 7, false, ImportExistingSlotPolicy.PRESERVE_EXISTING)))
                .containsExactly(new PreferredExportSlotResolution(
                        101, 7, 1,
                        PreferredExportSlotResolution.Outcome.SURVIVOR_MISMATCH_PRESERVED));

        assertThatThrownBy(() -> preferred(new PreferredExportSlotRequest(
                101, 7, false, ImportExistingSlotPolicy.REJECT_MISMATCH)))
                .isInstanceOf(PreferredExportSlotConflictException.class)
                .satisfies(failure -> assertThat(((PreferredExportSlotConflictException) failure).reason())
                        .isEqualTo(PreferredExportSlotConflictException.Reason.SURVIVOR_MISMATCH));
        assertThat(assignments()).isEqualTo("101:1");
    }

    @Test
    void rejects_the_complete_duplicate_request_group_before_registry_mutation() throws Exception {
        addActive(1, 101);
        addActive(2, 102);

        assertThatThrownBy(() -> preferred(
                request(101, 5, true),
                request(102, 5, true)))
                .isInstanceOf(PreferredExportSlotConflictException.class)
                .satisfies(failure -> {
                    PreferredExportSlotConflictException conflict =
                            (PreferredExportSlotConflictException) failure;
                    assertThat(conflict.reason())
                            .isEqualTo(PreferredExportSlotConflictException.Reason.DUPLICATE_REQUEST);
                    assertThat(conflict.lifecycleIds()).containsExactly(101L, 102L);
                });
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_state")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_assignment")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_free_range")).isZero();
    }

    @Test
    void merges_released_neighbors_and_splits_ranges_at_start_middle_and_end() throws Exception {
        addActive(1, 101);
        addActive(2, 102);
        addActive(3, 103);
        reconcileOrdinary();

        expire(101);
        expire(103);
        reconcileOrdinary();
        assertThat(ranges()).isEqualTo("1-1,3-3");

        expire(102);
        reconcileOrdinary();
        assertThat(ranges()).isEqualTo("1-3");

        addActive(4, 104);
        preferred(request(104, 2, true));
        assertThat(ranges()).isEqualTo("1-1,3-3");

        addActive(5, 105);
        preferred(request(105, 1, true));
        assertThat(ranges()).isEqualTo("3-3");

        addActive(6, 106);
        preferred(request(106, 3, true));
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_free_range")).isZero();
    }

    @Test
    void rolls_back_preferred_assignment_ranges_and_canonical_row_together() throws Exception {
        addActive(1, 101);
        reconcileOrdinary();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            addActive(connection, 2, 102);
            registry.reconcilePreferred(
                    connection, PROFILE, ARTIFACT, NOW,
                    List.of(request(102, SPARSE_SLOT, true)));
            assertThat(count(connection, "export_slot_assignment")).isEqualTo(2);
            assertThat(count(connection, "export_slot_free_range")).isOne();
            connection.rollback();
        }

        assertThat(assignments()).isEqualTo("101:1");
        assertThat(queryLong("SELECT COUNT(*) FROM export_slot_free_range")).isZero();
        assertThat(queryLong("SELECT next_slot FROM export_slot_state")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM masks")).isOne();
    }

    private PreferredExportSlotRequest request(long lifecycleId, long slot, boolean newLifecycle) {
        return new PreferredExportSlotRequest(
                lifecycleId, slot, newLifecycle, ImportExistingSlotPolicy.PRESERVE_EXISTING);
    }

    private List<PreferredExportSlotResolution> preferred(PreferredExportSlotRequest... requests)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<PreferredExportSlotResolution> result = registry.reconcilePreferred(
                        connection, PROFILE, ARTIFACT, NOW, List.of(requests));
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void reconcileOrdinary() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            registry.reconcile(connection, plan(), NOW);
            connection.commit();
        }
    }

    private ExportPlan plan() {
        return new ExportPlan(
                1,
                new ExportProfile(PROFILE, ExportMode.COMPLETE, List.of(ARTIFACT)),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new ExportArtifactSpec(
                        ARTIFACT, "masks.csv", List.of("id", "mask"),
                        1, HASH, HASH, HASH)));
    }

    private void addActive(long id, long lifecycleId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            addActive(connection, id, lifecycleId);
        }
    }

    private void addActive(Connection connection, long id, long lifecycleId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO masks(
                    id, mask, row_key, _created_at, _first_source_key,
                    _lifecycle_id, _first_confirmed_at_epoch_ms,
                    _last_confirmed_at_epoch_ms, _valid_until_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, id);
            statement.setString(2, "mask-" + id + ".example");
            statement.setString(3, "row-" + id);
            statement.setString(4, NOW.toString());
            statement.setString(5, "source");
            statement.setLong(6, lifecycleId);
            statement.setLong(7, NOW.toEpochMilli());
            statement.setLong(8, NOW.toEpochMilli());
            statement.setLong(9, ACTIVE_UNTIL.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void expire(long lifecycleId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE masks SET _valid_until_epoch_ms = ? WHERE _lifecycle_id = ?
                     """)) {
            statement.setLong(1, NOW.toEpochMilli());
            statement.setLong(2, lifecycleId);
            statement.executeUpdate();
        }
    }

    private String ranges() throws Exception {
        return queryString("""
                SELECT group_concat(range_start || '-' || range_end, ',')
                FROM (
                    SELECT range_start, range_end
                    FROM export_slot_free_range
                    ORDER BY range_start)
                """);
    }

    private String assignments() throws Exception {
        return queryString("""
                SELECT group_concat(lifecycle_id || ':' || slot, ',')
                FROM (
                    SELECT lifecycle_id, slot
                    FROM export_slot_assignment
                    ORDER BY lifecycle_id)
                """);
    }

    private String queryString(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private long queryLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private long count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private List<String> explain(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            List<String> plan = new ArrayList<>();
            while (resultSet.next()) {
                plan.add(resultSet.getString("detail"));
            }
            return plan;
        }
    }
}
