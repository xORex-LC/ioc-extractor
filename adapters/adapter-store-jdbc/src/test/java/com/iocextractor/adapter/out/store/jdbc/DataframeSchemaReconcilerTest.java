package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
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
    void creates_business_table_sources_table_and_last_seen_view() throws Exception {
        DataframeSchemaPlan plan = reconciler().reconcile(List.of(schema(
                "masks",
                column("id"),
                column("mask"),
                column("url_match"))));

        assertThat(plan.changes())
                .extracting(DataframeSchemaChange::kind)
                .containsExactly(
                        DataframeSchemaChange.Kind.CREATE_TABLE,
                        DataframeSchemaChange.Kind.CREATE_TABLE,
                        DataframeSchemaChange.Kind.CREATE_VIEW);
        assertThat(columnNames("masks"))
                .containsExactly("id", "mask", "url_match", "row_key", "_created_at", "_first_source_key");
        assertThat(columnNames("masks_sources"))
                .containsExactly("row_id", "source_key", "first_seen_at", "last_seen_at", "occurrences");
        assertThat(viewExists("masks_last_seen")).isTrue();
    }

    @Test
    void adds_missing_business_column_without_recreating_table() throws Exception {
        DataframeSchemaReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(schema("masks", column("mask"))));
        insertMaskRow("example.com");

        DataframeSchemaPlan plan = reconciler.reconcile(List.of(schema("masks", column("mask"), column("score"))));

        assertThat(plan.changes())
                .extracting(DataframeSchemaChange::kind)
                .containsExactly(DataframeSchemaChange.Kind.ADD_COLUMN);
        assertThat(columnNames("masks")).contains("score");
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

        assertThat(result.currentVersion()).isEqualTo(1);
        assertThat(tableExists("dataframe_schema_format")).isTrue();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT value FROM dataframe_schema_format WHERE name = 'format'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("dataframe-v1");
        }
    }

    private DataframeSchemaReconciler reconciler() {
        dataSource = dataSource("schema-" + System.nanoTime() + ".db");
        return new DataframeSchemaReconciler(dataSource);
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
