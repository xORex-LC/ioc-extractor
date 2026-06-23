package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteUserVersionSchemaMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void applies_service_schema_to_fresh_database() throws Exception {
        try (ManagedSqliteDataSource dataSource = dataSource("fresh.db")) {
            SchemaMigrationResult result = new SqliteUserVersionSchemaMigrator(
                    dataSource, ServiceSchemaMigrations.sqlite()).migrate();

            assertThat(result.previousVersion()).isZero();
            assertThat(result.currentVersion()).isEqualTo(1);
            assertThat(result.appliedVersions()).containsExactly(1);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(userVersion(connection)).isEqualTo(1);
                assertThat(tableExists(connection, "ingestion_ledger")).isTrue();
                assertThat(tableExists(connection, "ingestion_partition")).isTrue();
                assertThat(tableExists(connection, "legacy_imports")).isTrue();
                assertCascadeDelete(connection);
            }
        }
    }

    @Test
    void skips_when_schema_is_current() throws Exception {
        try (ManagedSqliteDataSource dataSource = dataSource("current.db")) {
            var migrator = new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite());
            migrator.migrate();

            SchemaMigrationResult result = migrator.migrate();

            assertThat(result.previousVersion()).isEqualTo(1);
            assertThat(result.currentVersion()).isEqualTo(1);
            assertThat(result.appliedVersions()).isEmpty();
        }
    }

    @Test
    void applies_only_missing_versions_from_partial_database() throws Exception {
        List<SqliteSchemaMigration> migrations = List.of(
                new SqliteSchemaMigration(1, "one", "CREATE TABLE one (id INTEGER PRIMARY KEY);"),
                new SqliteSchemaMigration(2, "two", "CREATE TABLE two (id INTEGER PRIMARY KEY);"));
        try (ManagedSqliteDataSource dataSource = dataSource("partial.db");
             Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE one (id INTEGER PRIMARY KEY)");
            connection.createStatement().execute("PRAGMA user_version=1");

            SchemaMigrationResult result = new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate();

            assertThat(result.previousVersion()).isEqualTo(1);
            assertThat(result.currentVersion()).isEqualTo(2);
            assertThat(result.appliedVersions()).containsExactly(2);
            assertThat(tableExists(connection, "one")).isTrue();
            assertThat(tableExists(connection, "two")).isTrue();
        }
    }

    @Test
    void refuses_downgrade_without_mutating_database() throws Exception {
        try (ManagedSqliteDataSource dataSource = dataSource("downgrade.db");
             Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE preserved (id INTEGER PRIMARY KEY)");
            connection.createStatement().execute("PRAGMA user_version=5");

            assertThatThrownBy(() -> new SqliteUserVersionSchemaMigrator(
                    dataSource, ServiceSchemaMigrations.sqlite()).migrate())
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessageContaining("newer than supported");

            assertThat(userVersion(connection)).isEqualTo(5);
            assertThat(tableExists(connection, "preserved")).isTrue();
        }
    }

    @Test
    void rolls_back_failed_migration_and_keeps_user_version() throws Exception {
        List<SqliteSchemaMigration> migrations = List.of(new SqliteSchemaMigration(1, "broken",
                "CREATE TABLE created_before_failure (id INTEGER PRIMARY KEY);"
                        + "CREATE TABLE broken ("));
        try (ManagedSqliteDataSource dataSource = dataSource("rollback.db");
             Connection connection = dataSource.getConnection()) {

            assertThatThrownBy(() -> new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate())
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessageContaining("Failed to apply SQLite schema migration v1");

            assertThat(userVersion(connection)).isZero();
            assertThat(tableExists(connection, "created_before_failure")).isFalse();
        }
    }

    private ManagedSqliteDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }

    private int userVersion(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA user_version")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM sqlite_master
                WHERE type IN ('table', 'index') AND name = ?
                """)) {
            statement.setString(1, table);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void assertCascadeDelete(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO ingestion_ledger (
                        source_key, status, original_path, processing_path, detected_at, updated_at
                    ) VALUES ('abc', 'SOURCE_ARCHIVED', 'inbox/a.htm', 'processing/a.htm',
                              '2026-06-23T00:00:00Z', '2026-06-23T00:00:01Z')
                    """);
            statement.execute("""
                    INSERT INTO ingestion_partition (source_key, partition_path)
                    VALUES ('abc', 'dataframe/partitions/masks/abc.csv')
                    """);
            statement.execute("DELETE FROM ingestion_ledger WHERE source_key = 'abc'");
            try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM ingestion_partition")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isZero();
            }
        }
    }
}
