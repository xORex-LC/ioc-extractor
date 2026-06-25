package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteUserVersionSchemaMigratorTest {

    private static final DiagnosticFactory DIAGNOSTICS = new DiagnosticFactory(
            Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneOffset.UTC));

    @TempDir
    Path tempDir;

    @Test
    void applies_service_schema_to_fresh_database() throws Exception {
        try (HikariDataSource dataSource = dataSource("fresh.db")) {
            var diagnostics = new CollectingDiagnosticSink();
            SchemaMigrationResult result = migrator(dataSource, diagnostics, ServiceSchemaMigrations.sqlite())
                    .migrate();

            assertThat(result.previousVersion()).isZero();
            assertThat(result.currentVersion()).isEqualTo(2);
            assertThat(result.appliedVersions()).containsExactly(1, 2);
            assertThat(diagnostics.diagnostics())
                    .extracting(diagnostic -> diagnostic.code())
                    .containsExactly(StorageDiagnosticCodes.MIGRATION_APPLIED,
                            StorageDiagnosticCodes.MIGRATION_APPLIED);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(userVersion(connection)).isEqualTo(2);
                assertThat(tableExists(connection, "ingestion_ledger")).isTrue();
                assertThat(tableExists(connection, "ingestion_partition")).isTrue();
                assertThat(tableExists(connection, "legacy_imports")).isTrue();
                assertThat(tableExists(connection, "aggregation_run")).isTrue();
                assertThat(tableExists(connection, "export_run")).isTrue();
                assertCascadeDelete(connection);
            }
        }
    }

    @Test
    void skips_when_schema_is_current() throws Exception {
        try (HikariDataSource dataSource = dataSource("current.db")) {
            var setupDiagnostics = new CollectingDiagnosticSink();
            var migrator = migrator(dataSource, setupDiagnostics, ServiceSchemaMigrations.sqlite());
            migrator.migrate();

            var diagnostics = new CollectingDiagnosticSink();
            migrator = migrator(dataSource, diagnostics, ServiceSchemaMigrations.sqlite());
            SchemaMigrationResult result = migrator.migrate();

            assertThat(result.previousVersion()).isEqualTo(2);
            assertThat(result.currentVersion()).isEqualTo(2);
            assertThat(result.appliedVersions()).isEmpty();
            assertThat(diagnostics.diagnostics()).isEmpty();
        }
    }

    @Test
    void applies_only_missing_versions_from_partial_database() throws Exception {
        List<SqliteSchemaMigration> migrations = List.of(
                new SqliteSchemaMigration(1, "one", "CREATE TABLE one (id INTEGER PRIMARY KEY);"),
                new SqliteSchemaMigration(2, "two", "CREATE TABLE two (id INTEGER PRIMARY KEY);"));
        try (HikariDataSource dataSource = dataSource("partial.db");
             Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE one (id INTEGER PRIMARY KEY)");
            connection.createStatement().execute("PRAGMA user_version=1");

            var diagnostics = new CollectingDiagnosticSink();
            SchemaMigrationResult result = migrator(dataSource, diagnostics, migrations).migrate();

            assertThat(result.previousVersion()).isEqualTo(1);
            assertThat(result.currentVersion()).isEqualTo(2);
            assertThat(result.appliedVersions()).containsExactly(2);
            assertThat(diagnostics.diagnostics())
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.code()).isEqualTo(StorageDiagnosticCodes.MIGRATION_APPLIED);
                        assertThat(diagnostic.context()).containsEntry("migrationVersion", 2);
                    });
            assertThat(tableExists(connection, "one")).isTrue();
            assertThat(tableExists(connection, "two")).isTrue();
        }
    }

    @Test
    void refuses_downgrade_without_mutating_database() throws Exception {
        try (HikariDataSource dataSource = dataSource("downgrade.db");
             Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE preserved (id INTEGER PRIMARY KEY)");
            connection.createStatement().execute("PRAGMA user_version=5");
            var diagnostics = new CollectingDiagnosticSink();

            assertThatThrownBy(() -> migrator(dataSource, diagnostics, ServiceSchemaMigrations.sqlite()).migrate())
                    .isInstanceOf(DiagnosticException.class)
                    .hasMessageContaining(StorageDiagnosticCodes.MIGRATION_DOWNGRADE.id());

            assertThat(diagnostics.diagnostics())
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.code()).isEqualTo(StorageDiagnosticCodes.MIGRATION_DOWNGRADE);
                        assertThat(diagnostic.context())
                                .containsEntry("fromVersion", 5)
                                .containsEntry("toVersion", 2);
                    });
            assertThat(userVersion(connection)).isEqualTo(5);
            assertThat(tableExists(connection, "preserved")).isTrue();
        }
    }

    @Test
    void rolls_back_failed_migration_and_keeps_user_version() throws Exception {
        List<SqliteSchemaMigration> migrations = List.of(new SqliteSchemaMigration(1, "broken",
                "CREATE TABLE created_before_failure (id INTEGER PRIMARY KEY);"
                        + "CREATE TABLE broken ("));
        try (HikariDataSource dataSource = dataSource("rollback.db");
             Connection connection = dataSource.getConnection()) {
            var diagnostics = new CollectingDiagnosticSink();

            assertThatThrownBy(() -> migrator(dataSource, diagnostics, migrations).migrate())
                    .isInstanceOf(DiagnosticException.class)
                    .hasMessageContaining(StorageDiagnosticCodes.MIGRATION_ROLLBACK.id());

            assertThat(diagnostics.diagnostics())
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.code()).isEqualTo(StorageDiagnosticCodes.MIGRATION_ROLLBACK);
                        assertThat(diagnostic.context()).containsEntry("migrationVersion", 1);
                    });
            assertThat(userVersion(connection)).isZero();
            assertThat(tableExists(connection, "created_before_failure")).isFalse();
        }
    }

    private SqliteUserVersionSchemaMigrator migrator(HikariDataSource dataSource,
                                                     CollectingDiagnosticSink diagnostics,
                                                     List<SqliteSchemaMigration> migrations) {
        return new SqliteUserVersionSchemaMigrator(dataSource, migrations, diagnostics, DIAGNOSTICS, "service");
    }

    private HikariDataSource dataSource(String fileName) {
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
