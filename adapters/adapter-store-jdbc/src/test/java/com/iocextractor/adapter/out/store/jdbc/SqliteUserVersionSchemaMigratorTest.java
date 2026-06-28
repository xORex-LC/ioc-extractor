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
            assertThat(result.currentVersion()).isEqualTo(6);
            assertThat(result.appliedVersions()).containsExactly(1, 2, 3, 4, 5, 6);
            assertThat(diagnostics.diagnostics())
                    .extracting(diagnostic -> diagnostic.code())
                    .containsExactly(StorageDiagnosticCodes.MIGRATION_APPLIED,
                            StorageDiagnosticCodes.MIGRATION_APPLIED,
                            StorageDiagnosticCodes.MIGRATION_APPLIED,
                            StorageDiagnosticCodes.MIGRATION_APPLIED,
                            StorageDiagnosticCodes.MIGRATION_APPLIED,
                            StorageDiagnosticCodes.MIGRATION_APPLIED);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(userVersion(connection)).isEqualTo(6);
                assertThat(tableExists(connection, "ingestion_ledger")).isTrue();
                assertThat(tableExists(connection, "ingestion_partition")).isFalse();
                assertThat(tableExists(connection, "legacy_imports")).isTrue();
                assertThat(tableExists(connection, "ingest_run")).isTrue();
                assertThat(tableExists(connection, "export_run")).isTrue();
                assertThat(tableExists(connection, "export_progress")).isTrue();
                assertThat(tableExists(connection, "ux_export_run_active_singleton")).isTrue();
                assertThat(tableExists(connection, "remote_fetch_ledger")).isTrue();
                assertThat(tableExists(connection, "publish_ledger")).isTrue();
                assertThat(tableExists(connection, "ix_publish_ledger_status")).isTrue();
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

            assertThat(result.previousVersion()).isEqualTo(6);
            assertThat(result.currentVersion()).isEqualTo(6);
            assertThat(result.appliedVersions()).isEmpty();
            assertThat(diagnostics.diagnostics()).isEmpty();
        }
    }

    @Test
    void upgrades_v4_service_database_without_losing_ingest_runs() throws Exception {
        try (HikariDataSource dataSource = dataSource("service-v4.db")) {
            List<SqliteSchemaMigration> migrations = ServiceSchemaMigrations.sqlite();
            migrator(dataSource, new CollectingDiagnosticSink(), migrations.subList(0, 4)).migrate();
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("""
                        INSERT INTO ingest_run(
                            run_id, status, artifacts, started_at, updated_at, reason, source_key)
                        VALUES (
                            'ingest-1', 'COMPLETED', 'masks',
                            '2026-06-28T00:00:00Z', '2026-06-28T00:00:00Z', NULL, 'source-1')
                        """);
            }

            SchemaMigrationResult result = migrator(
                    dataSource, new CollectingDiagnosticSink(), migrations).migrate();

            assertThat(result.previousVersion()).isEqualTo(4);
            assertThat(result.currentVersion()).isEqualTo(6);
            assertThat(result.appliedVersions()).containsExactly(5, 6);
            try (Connection connection = dataSource.getConnection();
                 var resultSet = connection.createStatement().executeQuery(
                         "SELECT status FROM ingest_run WHERE run_id = 'ingest-1'")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("COMPLETED");
                assertThat(tableExists(connection, "export_run")).isTrue();
                assertThat(tableExists(connection, "publish_ledger")).isTrue();
            }
        }
    }

    @Test
    void upgrades_v5_service_database_with_sync_ledgers_only() throws Exception {
        try (HikariDataSource dataSource = dataSource("service-v5.db")) {
            List<SqliteSchemaMigration> migrations = ServiceSchemaMigrations.sqlite();
            migrator(dataSource, new CollectingDiagnosticSink(), migrations.subList(0, 5)).migrate();

            SchemaMigrationResult result = migrator(
                    dataSource, new CollectingDiagnosticSink(), migrations).migrate();

            assertThat(result.previousVersion()).isEqualTo(5);
            assertThat(result.currentVersion()).isEqualTo(6);
            assertThat(result.appliedVersions()).containsExactly(6);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(tableExists(connection, "remote_fetch_ledger")).isTrue();
                assertThat(tableExists(connection, "publish_ledger")).isTrue();
            }
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
            connection.createStatement().execute("PRAGMA user_version=7");
            var diagnostics = new CollectingDiagnosticSink();

            assertThatThrownBy(() -> migrator(dataSource, diagnostics, ServiceSchemaMigrations.sqlite()).migrate())
                    .isInstanceOf(DiagnosticException.class)
                    .hasMessageContaining(StorageDiagnosticCodes.MIGRATION_DOWNGRADE.id());

            assertThat(diagnostics.diagnostics())
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.code()).isEqualTo(StorageDiagnosticCodes.MIGRATION_DOWNGRADE);
                        assertThat(diagnostic.context())
                                .containsEntry("fromVersion", 7)
                                .containsEntry("toVersion", 6);
                    });
            assertThat(userVersion(connection)).isEqualTo(7);
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

}
