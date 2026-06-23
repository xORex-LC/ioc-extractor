package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Applies versioned SQLite migrations using {@code PRAGMA user_version} as the
 * durable format version. Each migration version is applied in one transaction.
 */
public final class SqliteUserVersionSchemaMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteUserVersionSchemaMigrator.class);
    private static final String DEFAULT_DB_ROLE = "storage";

    private final DataSource dataSource;
    private final List<SqliteSchemaMigration> migrations;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final String dbRole;

    public SqliteUserVersionSchemaMigrator(DataSource dataSource, List<SqliteSchemaMigration> migrations) {
        this(dataSource, migrations, NoopDiagnosticSink.INSTANCE,
                new DiagnosticFactory(Clock.systemUTC()), DEFAULT_DB_ROLE);
    }

    public SqliteUserVersionSchemaMigrator(DataSource dataSource,
                                           List<SqliteSchemaMigration> migrations,
                                           DiagnosticSink diagnosticSink,
                                           DiagnosticFactory diagnosticFactory,
                                           String dbRole) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.migrations = validate(migrations);
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        if (dbRole == null || dbRole.isBlank()) {
            throw new IllegalArgumentException("dbRole is required");
        }
        this.dbRole = dbRole;
    }

    public SchemaMigrationResult migrate() {
        try (Connection connection = dataSource.getConnection()) {
            int previousVersion = userVersion(connection);
            int targetVersion = targetVersion();
            if (previousVersion > targetVersion) {
                Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.MIGRATION_DOWNGRADE)
                        .with("dbRole", dbRole)
                        .with("fromVersion", previousVersion)
                        .with("toVersion", targetVersion)
                        .build();
                diagnosticSink.emit(diagnostic);
                LogEvents.error(LOGGER)
                        .action(EventAction.SCHEMA_MIGRATE)
                        .outcome(EventOutcome.FAILURE)
                        .field(LogField.IOC_DB_ROLE, dbRole)
                        .field(LogField.IOC_SCHEMA_VERSION, previousVersion)
                        .message("sqlite schema downgrade refused")
                        .log();
                throw new DiagnosticException(diagnostic);
            }

            List<Integer> applied = new ArrayList<>();
            for (SqliteSchemaMigration migration : migrations) {
                if (migration.version() > previousVersion) {
                    apply(connection, migration);
                    applied.add(migration.version());
                }
            }
            if (applied.isEmpty()) {
                LogEvents.debug(LOGGER)
                        .action(EventAction.SCHEMA_MIGRATE)
                        .outcome(EventOutcome.UNKNOWN)
                        .field(LogField.EVENT_TYPE, "skipped")
                        .field(LogField.IOC_DB_ROLE, dbRole)
                        .field(LogField.IOC_SCHEMA_VERSION, targetVersion)
                        .message("sqlite schema already current")
                        .log();
            }
            return new SchemaMigrationResult(previousVersion, targetVersion, applied);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to migrate SQLite schema", e);
        }
    }

    private List<SqliteSchemaMigration> validate(List<SqliteSchemaMigration> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<SqliteSchemaMigration> sorted = source.stream()
                .sorted(Comparator.comparingInt(SqliteSchemaMigration::version))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            int expected = i + 1;
            int actual = sorted.get(i).version();
            if (actual != expected) {
                throw new IllegalArgumentException("SQLite migrations must be contiguous from version 1; missing "
                        + expected);
            }
        }
        return sorted;
    }

    private int targetVersion() {
        return migrations.isEmpty() ? 0 : migrations.getLast().version();
    }

    private int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA user_version")) {
            if (!resultSet.next()) {
                throw new SQLException("PRAGMA user_version returned no rows");
            }
            return resultSet.getInt(1);
        }
    }

    private void apply(Connection connection, SqliteSchemaMigration migration) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements(migration.sql())) {
                statement.execute(sql);
            }
            statement.execute("PRAGMA user_version=" + migration.version());
            connection.commit();
            emitApplied(migration);
        } catch (SQLException e) {
            rollback(connection, e);
            Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.MIGRATION_ROLLBACK)
                    .with("dbRole", dbRole)
                    .with("migrationVersion", migration.version())
                    .with("reason", e.getMessage())
                    .cause(e)
                    .build();
            diagnosticSink.emit(diagnostic);
            LogEvents.error(LOGGER)
                    .action(EventAction.SCHEMA_MIGRATE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_DB_ROLE, dbRole)
                    .field(LogField.IOC_MIGRATION_VERSION, migration.version())
                    .message("sqlite schema migration rolled back")
                    .log(e);
            throw new DiagnosticException(diagnostic);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void emitApplied(SqliteSchemaMigration migration) {
        Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.MIGRATION_APPLIED)
                .with("dbRole", dbRole)
                .with("migrationVersion", migration.version())
                .with("schemaVersion", migration.version())
                .build();
        diagnosticSink.emit(diagnostic);
        LogEvents.info(LOGGER)
                .action(EventAction.SCHEMA_MIGRATE)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_DB_ROLE, dbRole)
                .field(LogField.IOC_MIGRATION_VERSION, migration.version())
                .field(LogField.IOC_SCHEMA_VERSION, migration.version())
                .message("sqlite schema migration applied")
                .log();
    }

    private void rollback(Connection connection, SQLException original) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private List<String> statements(String script) {
        // vN.sql files in the service schema are limited to simple DDL
        // statements. Replace this splitter before adding triggers, BEGIN...END
        // blocks, or semicolons inside string literals.
        String cleaned = script.lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(cleaned.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }
}
