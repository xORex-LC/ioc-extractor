package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Probes a JDBC storage role without depending on Actuator. Bootstrap maps the
 * returned VO to a health contributor.
 */
public final class JdbcStorageHealthProbe {

    private static final String EXPECTED_JOURNAL_MODE = "wal";
    private static final String EXPECTED_QUICK_CHECK = "ok";

    private final DataSource dataSource;
    private final String dbRole;

    public JdbcStorageHealthProbe(DataSource dataSource, String dbRole) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (dbRole == null || dbRole.isBlank()) {
            throw new IllegalArgumentException("dbRole is required");
        }
        this.dbRole = dbRole;
    }

    public JdbcStorageHealth probe() {
        try (Connection connection = dataSource.getConnection()) {
            Integer userVersion = intPragma(connection, "user_version");
            boolean foreignKeys = intPragma(connection, "foreign_keys") == 1;
            String journalMode = textPragma(connection, "journal_mode");
            String quickCheck = textPragma(connection, "quick_check");
            boolean healthy = foreignKeys
                    && EXPECTED_JOURNAL_MODE.equals(normalize(journalMode))
                    && EXPECTED_QUICK_CHECK.equals(normalize(quickCheck));
            return new JdbcStorageHealth(healthy, dbRole, userVersion, foreignKeys, journalMode, quickCheck, null);
        } catch (SQLException | RuntimeException e) {
            return new JdbcStorageHealth(false, dbRole, null, null, null, null, e.getMessage());
        }
    }

    private int intPragma(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA " + name)) {
            if (!resultSet.next()) {
                throw new SQLException("PRAGMA " + name + " returned no rows");
            }
            return resultSet.getInt(1);
        }
    }

    private String textPragma(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA " + name)) {
            if (!resultSet.next()) {
                throw new SQLException("PRAGMA " + name + " returned no rows");
            }
            return resultSet.getString(1);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
