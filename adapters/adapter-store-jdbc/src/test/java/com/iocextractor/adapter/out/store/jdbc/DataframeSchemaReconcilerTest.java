package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SchemaDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeSchemaReconcilerTest {

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
    void creates_active_sources_history_receipt_and_indexed_lifecycle_schema() throws Exception {
        DataframeSchemaPlan plan = reconciler().reconcile(List.of(schema(
                "masks",
                column("id"),
                column("mask"),
                column("url_match"))));

        assertThat(plan.changes())
                .extracting(change -> change.kind())
                .containsExactly(
                        DataframeSchemaChange.Kind.CREATE_TABLE,
                        DataframeSchemaChange.Kind.CREATE_TABLE,
                        DataframeSchemaChange.Kind.CREATE_VIEW,
                        DataframeSchemaChange.Kind.CREATE_INDEX,
                        DataframeSchemaChange.Kind.CREATE_INDEX,
                        DataframeSchemaChange.Kind.CREATE_TABLE,
                        DataframeSchemaChange.Kind.CREATE_TABLE,
                        DataframeSchemaChange.Kind.CREATE_INDEX,
                        DataframeSchemaChange.Kind.CREATE_TABLE);
        assertThat(columnNames("masks"))
                .containsExactly(
                        "id", "mask", "url_match", "row_key", "_created_at", "_first_source_key",
                        "_lifecycle_id", "_first_confirmed_at_epoch_ms",
                        "_last_confirmed_at_epoch_ms", "_valid_until_epoch_ms");
        assertThat(columnNames("masks_sources"))
                .containsExactly("row_id", "source_key", "first_seen_at", "last_seen_at", "occurrences");
        assertThat(columnNames("masks_history"))
                .containsExactly(
                        "history_id", "former_row_id", "row_key", "_lifecycle_id",
                        "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms",
                        "_valid_until_epoch_ms", "closed_at_epoch_ms", "close_reason",
                        "id", "mask", "url_match");
        assertThat(columnNames("masks_history_sources"))
                .containsExactly("history_id", "source_key", "first_seen_at", "last_seen_at", "occurrences");
        assertThat(columnNames("masks_receipt_rows"))
                .containsExactly("receipt_id", "ordinal", "row_key", "source_key", "observed_at_ms",
                        "mask", "url_match");
        assertThat(viewExists("masks_last_seen")).isTrue();
        assertThat(indexExists("ux_masks_lifecycle_id")).isTrue();
        assertThat(indexExists("ix_masks_lifecycle_due")).isTrue();
        assertThat(indexExists("ix_masks_history_retention")).isTrue();
    }

    @Test
    void adds_missing_business_column_without_recreating_table() throws Exception {
        DataframeSchemaReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(schema("masks", column("mask"))));
        insertMaskRow("example.com");

        DataframeSchemaPlan plan = reconciler.reconcile(List.of(schema("masks", column("mask"), column("score"))));

        assertThat(plan.changes())
                .extracting(change -> change.kind())
                .containsExactly(
                        DataframeSchemaChange.Kind.ADD_COLUMN,
                        DataframeSchemaChange.Kind.ADD_COLUMN,
                        DataframeSchemaChange.Kind.ADD_COLUMN);
        assertThat(columnNames("masks")).contains("score");
        assertThat(columnNames("masks_history")).contains("score");
        assertThat(columnNames("masks_receipt_rows")).contains("score");
        assertThat(rowCount("masks")).isOne();
    }

    @Test
    void reorder_of_business_columns_is_noop() {
        DataframeSchemaReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(schema("masks", column("mask"), column("score"))));

        DataframeSchemaPlan plan = reconciler.reconcile(List.of(schema("masks", column("score"), column("mask"))));

        assertThat(plan.empty()).isTrue();
    }

    @Test
    void drop_or_rename_drift_halts_before_mutation() throws Exception {
        DataframeSchemaReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(schema("masks", column("mask"), column("score"))));

        assertThatThrownBy(() -> reconciler.reconcile(List.of(schema("masks", column("mask")))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("unexpected column score");
        assertThat(columnNames("masks")).contains("score");
    }

    @Test
    void type_change_drift_halts_before_mutation() {
        DataframeSchemaReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(schema("masks", column("mask", "TEXT"))));

        assertThatThrownBy(() -> reconciler.reconcile(List.of(schema("masks", column("mask", "INTEGER")))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("type TEXT -> INTEGER");
    }

    @Test
    void lifecycle_index_definition_drift_is_not_silently_accepted() throws Exception {
        DataframeSchemaReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(schema("masks", column("mask"))));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP INDEX ix_masks_lifecycle_due");
            statement.execute("CREATE INDEX ix_masks_lifecycle_due ON masks(row_key)");
        }

        assertThatThrownBy(() -> reconciler.reconcile(List.of(schema("masks", column("mask")))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Lifecycle index definition drift");
    }

    @Test
    void reserved_prefix_columns_are_excluded_from_destructive_drift() throws Exception {
        DataframeSchemaReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(schema("masks", column("mask"))));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE masks ADD COLUMN _legacy_note TEXT");
        }

        DataframeSchemaPlan plan = reconciler.reconcile(List.of(schema("masks", column("mask"))));

        assertThat(plan.empty()).isTrue();
    }

    @Test
    void dry_run_reports_additive_changes_without_mutating_database() throws Exception {
        DataframeSchemaPlan plan = reconciler().dryRun(List.of(schema("hashes", column("hash_sha256"))));

        assertThat(plan.empty()).isFalse();
        assertThat(tableExists("hashes")).isFalse();
        assertThat(tableExists("hashes_sources")).isFalse();
    }

    @Test
    void format_migration_sets_dataframe_user_version() throws Exception {
        dataSource = dataSource("format.db");

        SchemaMigrationResult result = new SqliteUserVersionSchemaMigrator(
                dataSource,
                DataframeFormatMigrations.sqlite()).migrate();

        assertThat(result.currentVersion()).isEqualTo(5);
        assertThat(tableExists("dataframe_schema_format")).isTrue();
        assertThat(tableExists("artifact_identity")).isTrue();
        assertThat(tableExists("artifact_revision")).isTrue();
        assertThat(tableExists("canonical_lifecycle_control")).isTrue();
        assertThat(tableExists("lifecycle_id_allocator")).isTrue();
        assertThat(tableExists("artifact_id_allocator")).isTrue();
        assertThat(tableExists("artifact_projection_state")).isTrue();
        assertThat(tableExists("confirmation_receipt")).isTrue();
        assertThat(tableExists("confirmation_receipt_artifact")).isTrue();
        assertThat(tableExists("export_slot_assignment")).isTrue();
        assertThat(tableExists("export_slot_free")).isTrue();
        assertThat(tableExists("export_slot_state")).isTrue();
        assertThat(indexExists("ix_export_slot_assignment_slot")).isTrue();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT value FROM dataframe_schema_format WHERE name = 'format'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("dataframe-v1");
        }
    }

    @Test
    void format_migration_upgrades_v2_database_without_recreating_existing_tables() throws Exception {
        dataSource = dataSource("format-v2.db");
        List<SqliteSchemaMigration> migrations = DataframeFormatMigrations.sqlite();
        new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 2)).migrate();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO artifact_identity(artifact, identity_hash, epoch, applied_at) "
                    + "VALUES ('masks', 'hash', 1, '2026-06-28T00:00:00Z')");
        }

        SchemaMigrationResult result = new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate();

        assertThat(result.previousVersion()).isEqualTo(2);
        assertThat(result.currentVersion()).isEqualTo(5);
        assertThat(result.appliedVersions()).containsExactly(3, 4, 5);
        assertThat(tableExists("artifact_revision")).isTrue();
        assertThat(tableExists("canonical_lifecycle_control")).isTrue();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT epoch FROM artifact_identity WHERE artifact = 'masks'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isOne();
        }
    }

    @Test
    void format_migration_upgrades_v4_database_with_empty_export_slot_registry() throws Exception {
        dataSource = dataSource("format-v4.db");
        List<SqliteSchemaMigration> migrations = DataframeFormatMigrations.sqlite();
        new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 4)).migrate();

        SchemaMigrationResult result = new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate();

        assertThat(result.previousVersion()).isEqualTo(4);
        assertThat(result.currentVersion()).isEqualTo(5);
        assertThat(result.appliedVersions()).containsExactly(5);
        assertThat(tableExists("export_slot_assignment")).isTrue();
        assertThat(tableExists("export_slot_free")).isTrue();
        assertThat(tableExists("export_slot_state")).isTrue();
        assertThat(rowCount("export_slot_state")).isZero();
    }

    @Test
    void emits_schema_added_diagnostic_when_column_added() {
        CollectingDiagnosticSink sink = new CollectingDiagnosticSink();
        DataframeSchemaReconciler reconciler = diagnosticReconciler(sink);
        reconciler.reconcile(List.of(schema("masks", column("mask"))));

        reconciler.reconcile(List.of(schema("masks", column("mask"), column("score"))));

        assertThat(sink.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .contains(SchemaDiagnosticCodes.SCHEMA_ADDED.id());
    }

    @Test
    void emits_destructive_change_diagnostic_before_refusing_drift() {
        CollectingDiagnosticSink sink = new CollectingDiagnosticSink();
        DataframeSchemaReconciler reconciler = diagnosticReconciler(sink);
        reconciler.reconcile(List.of(schema("masks", column("mask"), column("score"))));

        assertThatThrownBy(() -> reconciler.reconcile(List.of(schema("masks", column("mask")))))
                .isInstanceOf(IocExtractorException.class);
        assertThat(sink.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .contains(SchemaDiagnosticCodes.SCHEMA_DESTRUCTIVE_CHANGE.id());
    }

    private DataframeSchemaReconciler reconciler() {
        dataSource = dataSource("schema-" + System.nanoTime() + ".db");
        return new DataframeSchemaReconciler(dataSource);
    }

    private DataframeSchemaReconciler diagnosticReconciler(CollectingDiagnosticSink sink) {
        dataSource = dataSource("schema-" + System.nanoTime() + ".db");
        return new DataframeSchemaReconciler(
                dataSource, sink, new DiagnosticFactory(Clock.systemUTC()), "dataframe");
    }

    private DataframeArtifactSchema schema(String artifactName, DataframeColumn... columns) {
        return new DataframeArtifactSchema(artifactName, List.of(columns));
    }

    private DataframeColumn column(String name) {
        return column(name, "TEXT");
    }

    private DataframeColumn column(String name, String type) {
        return new DataframeColumn(name, type);
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }

    private List<String> columnNames(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            var columns = new java.util.ArrayList<String>();
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
            return columns;
        }
    }

    private void insertMaskRow(String mask) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO masks(mask, row_key, _created_at)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setString(1, mask);
            statement.setString(2, "row:" + mask);
            statement.setString(3, "2026-06-24T00:00:00Z");
            statement.executeUpdate();
        }
    }

    private int rowCount(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private boolean tableExists(String name) throws SQLException {
        return objectExists("table", name);
    }

    private boolean viewExists(String name) throws SQLException {
        return objectExists("view", name);
    }

    private boolean indexExists(String name) throws SQLException {
        return objectExists("index", name);
    }

    private boolean objectExists(String type, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT 1
                     FROM sqlite_master
                     WHERE type = ? AND name = ?
                     """)) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
