package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Plans additive lifecycle, history and receipt schema for one artifact. */
final class LifecycleArtifactSchemaPlanner {

    private static final List<ColumnDefinition> ACTIVE_LIFECYCLE_COLUMNS = List.of(
            new ColumnDefinition("_lifecycle_id", "INTEGER"),
            new ColumnDefinition("_first_confirmed_at_epoch_ms", "INTEGER"),
            new ColumnDefinition("_last_confirmed_at_epoch_ms", "INTEGER"),
            new ColumnDefinition("_valid_until_epoch_ms", "INTEGER"));

    void plan(Connection connection,
              DataframeArtifactSchema schema,
              boolean activeTableCreated,
              List<DataframeSchemaChange> changes) throws SQLException {
        String artifact = schema.artifactName();
        if (!activeTableCreated) {
            planActiveColumns(connection, artifact, changes);
        }
        planIndex(connection, lifecycleIdentityIndex(artifact), artifact,
                "CREATE UNIQUE INDEX " + quote(lifecycleIdentityIndex(artifact))
                        + " ON " + quote(artifact) + " (" + quote("_lifecycle_id") + ")",
                changes);
        planIndex(connection, lifecycleDueIndex(artifact), artifact,
                "CREATE INDEX " + quote(lifecycleDueIndex(artifact))
                        + " ON " + quote(artifact) + " ("
                        + quote("_valid_until_epoch_ms") + ", " + quote("_lifecycle_id") + ")",
                changes);
        planHistory(connection, schema, changes);
        planReceiptRows(connection, schema, changes);
    }

    private void planActiveColumns(Connection connection,
                                   String artifact,
                                   List<DataframeSchemaChange> changes) throws SQLException {
        Map<String, ExistingColumn> existing = columns(connection, artifact);
        for (ColumnDefinition lifecycleColumn : ACTIVE_LIFECYCLE_COLUMNS) {
            ExistingColumn current = existing.get(lifecycleColumn.name());
            if (current == null) {
                changes.add(addColumn(artifact, lifecycleColumn.name(), lifecycleColumn.type()));
            } else {
                requireType(artifact, current, lifecycleColumn.type());
            }
        }
    }

    private void planHistory(Connection connection,
                             DataframeArtifactSchema schema,
                             List<DataframeSchemaChange> changes) throws SQLException {
        String historyTable = historyTable(schema.artifactName());
        Map<String, String> desired = historyColumns(schema);
        if (!tableExists(connection, historyTable)) {
            changes.add(new DataframeSchemaChange(
                    DataframeSchemaChange.Kind.CREATE_TABLE,
                    historyTable,
                    null,
                    createTableSql(historyTable, desired, historyConstraints())));
        } else {
            planTypedAdditions(connection, historyTable, desired, historyInternalColumns(), changes);
        }

        String historySources = historySourcesTable(schema.artifactName());
        if (!tableExists(connection, historySources)) {
            changes.add(new DataframeSchemaChange(
                    DataframeSchemaChange.Kind.CREATE_TABLE,
                    historySources,
                    null,
                    createHistorySourcesSql(schema.artifactName())));
        }
        planIndex(connection, historyRetentionIndex(schema.artifactName()), historyTable,
                "CREATE INDEX " + quote(historyRetentionIndex(schema.artifactName()))
                        + " ON " + quote(historyTable) + " ("
                        + quote("closed_at_epoch_ms") + ", " + quote("history_id") + ")",
                changes);
    }

    private void planReceiptRows(Connection connection,
                                 DataframeArtifactSchema schema,
                                 List<DataframeSchemaChange> changes) throws SQLException {
        String receiptTable = receiptRowsTable(schema.artifactName());
        Map<String, String> desired = receiptColumns(schema);
        if (!tableExists(connection, receiptTable)) {
            changes.add(new DataframeSchemaChange(
                    DataframeSchemaChange.Kind.CREATE_TABLE,
                    receiptTable,
                    null,
                    createTableSql(receiptTable, desired, receiptConstraints())));
        } else {
            planTypedAdditions(connection, receiptTable, desired, receiptInternalColumns(), changes);
        }
    }

    private void planTypedAdditions(Connection connection,
                                    String table,
                                    Map<String, String> desired,
                                    List<String> requiredInternalColumns,
                                    List<DataframeSchemaChange> changes) throws SQLException {
        Map<String, ExistingColumn> existing = columns(connection, table);
        for (String internal : requiredInternalColumns) {
            if (!existing.containsKey(internal)) {
                throw new IocExtractorException(
                        "Lifecycle schema is missing required column " + table + "." + internal);
            }
        }
        for (Map.Entry<String, String> desiredColumn : desired.entrySet()) {
            ExistingColumn current = existing.get(desiredColumn.getKey());
            if (current == null) {
                changes.add(addColumn(table, desiredColumn.getKey(), nullableType(desiredColumn.getValue())));
            } else {
                requireType(table, current, desiredColumn.getValue());
            }
        }
    }

    private Map<String, String> historyColumns(DataframeArtifactSchema schema) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("history_id", "INTEGER PRIMARY KEY AUTOINCREMENT");
        columns.put("former_row_id", "INTEGER NOT NULL");
        columns.put("row_key", "TEXT NOT NULL");
        columns.put("_lifecycle_id", "INTEGER NOT NULL");
        columns.put("_first_confirmed_at_epoch_ms", "INTEGER NOT NULL");
        columns.put("_last_confirmed_at_epoch_ms", "INTEGER NOT NULL");
        columns.put("_valid_until_epoch_ms", "INTEGER NOT NULL");
        columns.put("closed_at_epoch_ms", "INTEGER NOT NULL");
        columns.put("close_reason", "TEXT NOT NULL");
        for (DataframeColumn column : schema.columns()) {
            columns.put(column.name(), historyBusinessType(column));
        }
        return columns;
    }

    private Map<String, String> receiptColumns(DataframeArtifactSchema schema) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("receipt_id", "TEXT NOT NULL");
        columns.put("ordinal", "INTEGER NOT NULL CHECK (ordinal >= 0)");
        columns.put("row_key", "TEXT NOT NULL");
        columns.put("source_key", "TEXT NOT NULL");
        columns.put("observed_at_ms", "INTEGER NOT NULL");
        for (DataframeColumn column : schema.columns()) {
            if (!"id".equals(column.name())) {
                columns.put(column.name(), column.sqlType());
            }
        }
        return columns;
    }

    private List<String> historyConstraints() {
        return List.of(
                "UNIQUE (" + quote("_lifecycle_id") + ")",
                "CHECK (" + quote("close_reason") + " IN ('EXPIRED', 'LEGACY_ACTIVATION'))");
    }

    private List<String> receiptConstraints() {
        return List.of(
                "PRIMARY KEY (" + quote("receipt_id") + ", " + quote("ordinal") + ")",
                "UNIQUE (" + quote("receipt_id") + ", " + quote("row_key") + ")",
                "FOREIGN KEY (" + quote("receipt_id") + ") REFERENCES "
                        + quote("confirmation_receipt") + "(" + quote("receipt_id") + ") ON DELETE CASCADE");
    }

    private String createHistorySourcesSql(String artifact) {
        String table = historySourcesTable(artifact);
        return "CREATE TABLE " + quote(table) + " (\n"
                + "    " + quote("history_id") + " INTEGER NOT NULL REFERENCES "
                + quote(historyTable(artifact)) + "(" + quote("history_id") + ") ON DELETE CASCADE,\n"
                + "    " + quote("source_key") + " TEXT NOT NULL,\n"
                + "    " + quote("first_seen_at") + " TEXT NOT NULL,\n"
                + "    " + quote("last_seen_at") + " TEXT NOT NULL,\n"
                + "    " + quote("occurrences") + " INTEGER NOT NULL CHECK ("
                + quote("occurrences") + " > 0),\n"
                + "    PRIMARY KEY (" + quote("history_id") + ", " + quote("source_key") + ")\n"
                + ")";
    }

    private String createTableSql(String table,
                                  Map<String, String> columns,
                                  List<String> constraints) {
        List<String> definitions = new ArrayList<>(columns.size() + constraints.size());
        columns.forEach((name, type) -> definitions.add(quote(name) + " " + type));
        definitions.addAll(constraints);
        return "CREATE TABLE " + quote(table) + " (\n    "
                + String.join(",\n    ", definitions)
                + "\n)";
    }

    private DataframeSchemaChange addColumn(String table, String column, String type) {
        return new DataframeSchemaChange(
                DataframeSchemaChange.Kind.ADD_COLUMN,
                table,
                column,
                "ALTER TABLE " + quote(table) + " ADD COLUMN " + quote(column) + " " + type);
    }

    private void planIndex(Connection connection,
                           String index,
                           String table,
                           String sql,
                           List<DataframeSchemaChange> changes) throws SQLException {
        String existingSql = indexSql(connection, index);
        if (existingSql == null) {
            changes.add(new DataframeSchemaChange(
                    DataframeSchemaChange.Kind.CREATE_INDEX, table, null, sql));
        } else if (!normalizeSql(existingSql).equals(normalizeSql(sql))) {
            throw new IocExtractorException("Lifecycle index definition drift for " + index);
        }
    }

    private Map<String, ExistingColumn> columns(Connection connection, String table) throws SQLException {
        Map<String, ExistingColumn> columns = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(
                "SELECT name, type FROM pragma_table_info(?)")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("name");
                    columns.put(name, new ExistingColumn(name, normalizeType(resultSet.getString("type"))));
                }
            }
        }
        return columns;
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        return objectExists(connection, "table", table);
    }

    private String indexSql(Connection connection, String index) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?
                """)) {
            statement.setString(1, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("sql") : null;
            }
        }
    }

    private boolean objectExists(Connection connection, String type, String name) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?
                """)) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void requireType(String table, ExistingColumn existing, String desiredType) {
        String desired = normalizeType(nullableType(desiredType));
        if (!existing.type().equals(desired)) {
            throw new IocExtractorException("Lifecycle schema type drift for " + table + "."
                    + existing.name() + ": " + existing.type() + " -> " + desired);
        }
    }

    private String historyBusinessType(DataframeColumn column) {
        return "id".equals(column.name()) ? "INTEGER" : column.sqlType();
    }

    private String nullableType(String definition) {
        int separator = definition.indexOf(' ');
        return separator < 0 ? definition : definition.substring(0, separator);
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "TEXT";
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        int paren = normalized.indexOf('(');
        return paren < 0 ? normalized : normalized.substring(0, paren);
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private List<String> historyInternalColumns() {
        return List.of("history_id", "former_row_id", "row_key", "_lifecycle_id",
                "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms",
                "_valid_until_epoch_ms", "closed_at_epoch_ms", "close_reason");
    }

    private List<String> receiptInternalColumns() {
        return List.of("receipt_id", "ordinal", "row_key", "source_key", "observed_at_ms");
    }

    private String lifecycleIdentityIndex(String artifact) {
        return "ux_" + artifact + "_lifecycle_id";
    }

    private String lifecycleDueIndex(String artifact) {
        return "ix_" + artifact + "_lifecycle_due";
    }

    private String historyTable(String artifact) {
        return artifact + "_history";
    }

    private String historySourcesTable(String artifact) {
        return artifact + "_history_sources";
    }

    private String historyRetentionIndex(String artifact) {
        return "ix_" + artifact + "_history_retention";
    }

    private String receiptRowsTable(String artifact) {
        return artifact + "_receipt_rows";
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private record ExistingColumn(String name, String type) {
    }

    private record ColumnDefinition(String name, String type) {
    }
}
