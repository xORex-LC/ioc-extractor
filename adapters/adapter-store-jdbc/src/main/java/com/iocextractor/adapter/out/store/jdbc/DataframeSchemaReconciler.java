package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SchemaDiagnosticCodes;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles configured artifact schemas into SQLite dataframe tables.
 *
 * <p>The reconciler is deliberately additive-only: missing tables/columns are
 * planned, reorder is ignored, and destructive drift (drop/rename/type change)
 * fails before any DDL is executed.
 */
public final class DataframeSchemaReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataframeSchemaReconciler.class);

    private final DataSource dataSource;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final String dbRole;

    public DataframeSchemaReconciler(DataSource dataSource) {
        this(dataSource, NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(Clock.systemUTC()), "dataframe");
    }

    public DataframeSchemaReconciler(DataSource dataSource,
                                     DiagnosticSink diagnosticSink,
                                     DiagnosticFactory diagnosticFactory,
                                     String dbRole) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        if (dbRole == null || dbRole.isBlank()) {
            throw new IllegalArgumentException("dbRole is required");
        }
        this.dbRole = dbRole;
    }

    public DataframeSchemaPlan dryRun(List<DataframeArtifactSchema> schemas) {
        try (Connection connection = dataSource.getConnection()) {
            return plan(connection, schemas);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to inspect dataframe schema", e);
        }
    }

    public DataframeSchemaPlan reconcile(List<DataframeArtifactSchema> schemas) {
        try (Connection connection = dataSource.getConnection()) {
            DataframeSchemaPlan plan = plan(connection, schemas);
            apply(connection, plan);
            emitApplied(plan);
            return plan;
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to reconcile dataframe schema", e);
        }
    }

    private DataframeSchemaPlan plan(Connection connection, List<DataframeArtifactSchema> schemas)
            throws SQLException {
        List<DataframeSchemaChange> changes = new ArrayList<>();
        for (DataframeArtifactSchema schema : schemas) {
            planArtifact(connection, schema, changes);
        }
        return new DataframeSchemaPlan(changes);
    }

    private void planArtifact(Connection connection,
                              DataframeArtifactSchema schema,
                              List<DataframeSchemaChange> changes) throws SQLException {
        String table = schema.artifactName();
        Map<String, ExistingColumn> existing = columns(connection, table);
        if (existing.isEmpty()) {
            changes.add(new DataframeSchemaChange(
                    DataframeSchemaChange.Kind.CREATE_TABLE,
                    table,
                    null,
                    createArtifactTableSql(schema)));
        } else {
            guardAgainstDestructiveDrift(schema, existing);
            for (DataframeColumn desired : businessColumns(schema)) {
                ExistingColumn current = existing.get(desired.name());
                if (current == null) {
                    changes.add(new DataframeSchemaChange(
                            DataframeSchemaChange.Kind.ADD_COLUMN,
                            table,
                            desired.name(),
                            "ALTER TABLE " + quote(table) + " ADD COLUMN "
                                    + quote(desired.name()) + " " + desired.sqlType()));
                } else if (!typesEqual(current.type(), desired.sqlType())) {
                    throw destructiveDrift(table, "type-change", "column " + desired.name()
                            + " type " + current.type() + " -> " + desired.sqlType());
                }
            }
        }

        String sourcesTable = sourcesTable(table);
        if (!tableExists(connection, sourcesTable)) {
            changes.add(new DataframeSchemaChange(
                    DataframeSchemaChange.Kind.CREATE_TABLE,
                    sourcesTable,
                    null,
                    createSourcesTableSql(table)));
        }
        String lastSeenView = lastSeenView(table);
        if (!viewExists(connection, lastSeenView)) {
            changes.add(new DataframeSchemaChange(
                    DataframeSchemaChange.Kind.CREATE_VIEW,
                    lastSeenView,
                    null,
                    createLastSeenViewSql(table)));
        }
    }

    private void guardAgainstDestructiveDrift(DataframeArtifactSchema schema,
                                              Map<String, ExistingColumn> existing) {
        Set<String> desired = new HashSet<>();
        desired.add("id");
        for (DataframeColumn column : businessColumns(schema)) {
            desired.add(column.name());
        }
        desired.add("row_key");
        desired.add("_created_at");
        desired.add("_first_source_key");

        for (ExistingColumn column : existing.values()) {
            if (column.name().startsWith("_")) {
                continue;
            }
            if (!desired.contains(column.name())) {
                throw destructiveDrift(schema.artifactName(), "drop-or-rename",
                        "unexpected column " + column.name());
            }
        }
    }

    private DiagnosticException destructiveDrift(String artifact, String change, String reason) {
        Diagnostic diagnostic = diagnosticFactory.create(SchemaDiagnosticCodes.SCHEMA_DESTRUCTIVE_CHANGE)
                .with("artifact", artifact)
                .with("change", change)
                .with("reason", reason)
                .build();
        diagnosticSink.emit(diagnostic);
        LogEvents.error(LOGGER)
                .action(EventAction.SCHEMA_VALIDATE)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_DB_ROLE, dbRole)
                .field(LogField.IOC_ARTIFACT_NAME, artifact)
                .message("destructive dataframe schema drift refused")
                .log();
        return new DiagnosticException(diagnostic);
    }

    private void emitApplied(DataframeSchemaPlan plan) {
        for (DataframeSchemaChange change : plan.changes()) {
            if (change.kind() == DataframeSchemaChange.Kind.ADD_COLUMN) {
                emitColumnAdded(change.tableName(), change.columnName());
            }
        }
    }

    private void emitColumnAdded(String artifact, String column) {
        Diagnostic diagnostic = diagnosticFactory.create(SchemaDiagnosticCodes.SCHEMA_ADDED)
                .with("artifact", artifact)
                .with("column", column)
                .build();
        diagnosticSink.emit(diagnostic);
        LogEvents.info(LOGGER)
                .action(EventAction.SCHEMA_VALIDATE)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_DB_ROLE, dbRole)
                .field(LogField.IOC_ARTIFACT_NAME, artifact)
                .message("dataframe schema column added")
                .log();
    }

    private List<DataframeColumn> businessColumns(DataframeArtifactSchema schema) {
        return schema.columns().stream()
                .filter(column -> !"id".equals(column.name()))
                .filter(column -> !column.name().startsWith("_"))
                .toList();
    }

    private void apply(Connection connection, DataframeSchemaPlan plan) throws SQLException {
        if (plan.empty()) {
            return;
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (DataframeSchemaChange change : plan.changes()) {
                statement.execute(change.sql());
            }
            connection.commit();
        } catch (SQLException e) {
            rollback(connection, e);
            throw e;
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

    private Map<String, ExistingColumn> columns(Connection connection, String table) throws SQLException {
        Map<String, ExistingColumn> columns = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                columns.put(name, new ExistingColumn(name, normalizeExistingType(resultSet.getString("type"))));
            }
        }
        return columns;
    }

    private boolean tableExists(Connection connection, String name) throws SQLException {
        return objectExists(connection, "table", name);
    }

    private boolean viewExists(Connection connection, String name) throws SQLException {
        return objectExists(connection, "view", name);
    }

    private boolean objectExists(Connection connection, String type, String name) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1
                FROM sqlite_master
                WHERE type = ? AND name = ?
                """)) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private String createArtifactTableSql(DataframeArtifactSchema schema) {
        List<String> columns = new ArrayList<>();
        columns.add(quote("id") + " INTEGER PRIMARY KEY AUTOINCREMENT");
        for (DataframeColumn column : businessColumns(schema)) {
            columns.add(quote(column.name()) + " " + column.sqlType());
        }
        columns.add(quote("row_key") + " TEXT NOT NULL UNIQUE");
        columns.add(quote("_created_at") + " TEXT NOT NULL");
        columns.add(quote("_first_source_key") + " TEXT");
        return "CREATE TABLE " + quote(schema.artifactName()) + " (\n    "
                + String.join(",\n    ", columns)
                + "\n)";
    }

    private String createSourcesTableSql(String artifactTable) {
        return "CREATE TABLE " + quote(sourcesTable(artifactTable)) + " (\n"
                + "    " + quote("row_id") + " INTEGER NOT NULL REFERENCES " + quote(artifactTable)
                + "(" + quote("id") + ") ON DELETE CASCADE,\n"
                + "    " + quote("source_key") + " TEXT NOT NULL,\n"
                + "    " + quote("first_seen_at") + " TEXT NOT NULL,\n"
                + "    " + quote("last_seen_at") + " TEXT NOT NULL,\n"
                + "    " + quote("occurrences") + " INTEGER NOT NULL DEFAULT 1,\n"
                + "    PRIMARY KEY (" + quote("row_id") + ", " + quote("source_key") + ")\n"
                + ")";
    }

    private String createLastSeenViewSql(String artifactTable) {
        return "CREATE VIEW " + quote(lastSeenView(artifactTable)) + " AS\n"
                + "SELECT " + quote("row_id") + ", MAX(" + quote("last_seen_at") + ") AS "
                + quote("last_seen_at") + "\n"
                + "FROM " + quote(sourcesTable(artifactTable)) + "\n"
                + "GROUP BY " + quote("row_id");
    }

    private String sourcesTable(String artifactTable) {
        return artifactTable + "_sources";
    }

    private String lastSeenView(String artifactTable) {
        return artifactTable + "_last_seen";
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private boolean typesEqual(String existing, String desired) {
        return normalizeExistingType(existing).equals(DataframeColumn.normalizeType(desired));
    }

    private String normalizeExistingType(String type) {
        if (type == null || type.isBlank()) {
            return "TEXT";
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        int paren = normalized.indexOf('(');
        return paren < 0 ? normalized : normalized.substring(0, paren);
    }

    private record ExistingColumn(String name, String type) {
    }
}
