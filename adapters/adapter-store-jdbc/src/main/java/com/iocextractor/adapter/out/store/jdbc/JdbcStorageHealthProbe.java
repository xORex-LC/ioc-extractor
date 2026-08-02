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
            Integer userVersion = intPragma(connection, IntegerPragma.USER_VERSION);
            boolean foreignKeys = intPragma(connection, IntegerPragma.FOREIGN_KEYS) == 1;
            String journalMode = textPragma(connection, TextPragma.JOURNAL_MODE);
            String quickCheck = textPragma(connection, TextPragma.QUICK_CHECK);
            boolean healthy = foreignKeys
                    && EXPECTED_JOURNAL_MODE.equals(normalize(journalMode))
                    && EXPECTED_QUICK_CHECK.equals(normalize(quickCheck));
            return new JdbcStorageHealth(healthy, dbRole, userVersion, foreignKeys, journalMode, quickCheck, null);
        } catch (SQLException | RuntimeException e) {
            return new JdbcStorageHealth(false, dbRole, null, null, null, null, e.getMessage());
        }
    }

    private int intPragma(Connection connection, IntegerPragma pragma) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var resultSet = switch (pragma) {
                case USER_VERSION -> statement.executeQuery("PRAGMA user_version");
                case FOREIGN_KEYS -> statement.executeQuery("PRAGMA foreign_keys");
            }) {
                if (!resultSet.next()) {
                    throw new SQLException(pragma + " returned no rows");
                }
                return resultSet.getInt(1);
            }
        }
    }

    private String textPragma(Connection connection, TextPragma pragma) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var resultSet = switch (pragma) {
                case JOURNAL_MODE -> statement.executeQuery("PRAGMA journal_mode");
                case QUICK_CHECK -> statement.executeQuery("PRAGMA quick_check");
            }) {
                if (!resultSet.next()) {
                    throw new SQLException(pragma + " returned no rows");
                }
                return resultSet.getString(1);
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private enum IntegerPragma {
        USER_VERSION,
        FOREIGN_KEYS
    }

    private enum TextPragma {
        JOURNAL_MODE,
        QUICK_CHECK
    }
}
