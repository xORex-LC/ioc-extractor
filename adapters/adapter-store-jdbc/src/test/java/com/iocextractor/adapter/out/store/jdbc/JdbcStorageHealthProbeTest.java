package com.iocextractor.adapter.out.store.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStorageHealthProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void reports_healthy_service_database_after_migration() {
        Path db = tempDir.resolve("healthy.db");
        try (HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + db, "low-memory", 1, 1))) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();

            JdbcStorageHealth health = new JdbcStorageHealthProbe(dataSource, "service").probe();

            assertThat(health.healthy()).isTrue();
            assertThat(health.dbRole()).isEqualTo("service");
            assertThat(health.userVersion()).isEqualTo(5);
            assertThat(health.foreignKeys()).isTrue();
            assertThat(health.journalMode()).isEqualToIgnoringCase("wal");
            assertThat(health.quickCheck()).isEqualToIgnoringCase("ok");
            assertThat(health.error()).isNull();
        }
    }

    @Test
    void reports_down_when_sqlite_policy_is_not_applied() {
        Path db = tempDir.resolve("unmanaged.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + db);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            JdbcStorageHealth health = new JdbcStorageHealthProbe(dataSource, "service").probe();

            assertThat(health.healthy()).isFalse();
            assertThat(health.foreignKeys()).isFalse();
            assertThat(health.journalMode()).isNotEqualToIgnoringCase("wal");
            assertThat(health.quickCheck()).isEqualToIgnoringCase("ok");
        }
    }
}
