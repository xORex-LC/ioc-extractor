package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.store.jdbc.SchemaMigrationResult;
import com.iocextractor.adapter.out.store.jdbc.ServiceSchemaMigrations;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Clock;
import java.util.Objects;

/**
 * Lazy owner of the service datasource and its mandatory schema migration.
 *
 * <p>The holder intentionally does not implement {@link javax.sql.DataSource}: Spring/Actuator
 * type discovery therefore cannot open service SQLite for unrelated CLI commands.
 */
public final class LazyServiceStorage implements AutoCloseable {

    private final IocProperties.Storage.Service settings;
    private final DiagnosticSink diagnosticSink;
    private final Clock clock;

    private HikariDataSource dataSource;
    private SchemaMigrationResult migration;

    /**
     * Creates an unopened service-storage owner.
     *
     * @param settings configured service storage settings
     * @param diagnosticSink migration diagnostic destination
     * @param clock migration diagnostic clock
     */
    public LazyServiceStorage(IocProperties.Storage.Service settings,
                              DiagnosticSink diagnosticSink,
                              Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Opens, migrates and returns the shared service datasource exactly once. */
    public synchronized HikariDataSource dataSource() {
        initialize();
        return dataSource;
    }

    /** Returns the migration checkpoint, initializing service storage when necessary. */
    public synchronized SchemaMigrationResult migration() {
        initialize();
        return migration;
    }

    private void initialize() {
        if (dataSource != null) {
            return;
        }
        HikariDataSource created = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", settings.url(), settings.sqlite().tuning(),
                        settings.pool().writeMax(), settings.pool().readMax()));
        try {
            SchemaMigrationResult migrated = new SqliteUserVersionSchemaMigrator(
                    created, ServiceSchemaMigrations.sqlite(), diagnosticSink,
                    new DiagnosticFactory(clock), "service").migrate();
            dataSource = created;
            migration = migrated;
        } catch (RuntimeException failure) {
            created.close();
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            migration = null;
        }
    }
}
