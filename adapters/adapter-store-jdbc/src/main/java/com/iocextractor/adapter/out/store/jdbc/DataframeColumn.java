package com.iocextractor.adapter.out.store.jdbc;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Desired dataframe business column. The storage adapter keeps SQL type
 * handling local so configuration and application code stay JDBC-free.
 */
public record DataframeColumn(String name, String sqlType) {

    private static final Set<String> SUPPORTED_TYPES = Set.of("TEXT", "INTEGER", "REAL", "BLOB", "NUMERIC");

    public DataframeColumn {
        name = requireIdentifier(name, "column name");
        sqlType = normalizeType(sqlType);
    }

    static String requireIdentifier(String value, String role) {
        Objects.requireNonNull(value, role);
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid dataframe " + role + ": " + value);
        }
        return value;
    }

    static String requireSqlIdentifier(String value, String role) {
        Objects.requireNonNull(value, role);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid dataframe " + role + ": " + value);
        }
        return value;
    }

    static String normalizeType(String value) {
        String normalized = (value == null || value.isBlank())
                ? "TEXT"
                : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported dataframe SQL type: " + value);
        }
        return normalized;
    }
}
