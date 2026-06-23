package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Applies versioned SQLite migrations using {@code PRAGMA user_version} as the
 * durable format version. Each migration version is applied in one transaction.
 */
public final class SqliteUserVersionSchemaMigrator {

    private final DataSource dataSource;
    private final List<SqliteSchemaMigration> migrations;

    public SqliteUserVersionSchemaMigrator(DataSource dataSource, List<SqliteSchemaMigration> migrations) {
        this.dataSource = dataSource;
        this.migrations = validate(migrations);
    }

    public SchemaMigrationResult migrate() {
        try (Connection connection = dataSource.getConnection()) {
            int previousVersion = userVersion(connection);
            int targetVersion = targetVersion();
            if (previousVersion > targetVersion) {
                throw new IocExtractorException("SQLite schema version " + previousVersion
                        + " is newer than supported version " + targetVersion);
            }

            List<Integer> applied = new ArrayList<>();
            for (SqliteSchemaMigration migration : migrations) {
                if (migration.version() > previousVersion) {
                    apply(connection, migration);
                    applied.add(migration.version());
                }
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
        } catch (SQLException e) {
            rollback(connection, e);
            throw new IocExtractorException("Failed to apply SQLite schema migration v"
                    + migration.version() + " (" + migration.name() + ")", e);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void rollback(Connection connection, SQLException original) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private List<String> statements(String script) {
        String cleaned = script.lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(cleaned.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }
}
