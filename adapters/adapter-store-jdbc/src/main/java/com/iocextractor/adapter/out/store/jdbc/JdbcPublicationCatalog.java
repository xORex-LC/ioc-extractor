package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;
import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;

/** Candidate-only typed catalog; persisted native shapes allow restart without inferring view primary keys. */
final class JdbcPublicationCatalog {
    private static final List<String> COMMON = List.of("canonical_match_alias", "canonical_lifecycle_field_origin",
            "canonical_lifecycle_field_origin_history", "artifact_revision", "artifact_projection_state",
            "export_slot_assignment", "export_slot_state", "export_slot_free_range", "import_commit",
            "import_commit_artifact", "import_row_rejection", "import_row_warning", "import_slot_resolution",
            "canonical_observation", "canonical_observation_commit", "confirmation_receipt",
            "confirmation_receipt_artifact", "confirmation_receipt_field_position");
    private JdbcPublicationCatalog() { }

    static List<JdbcPublicationTable> inspectNative(Connection connection, List<DataframeArtifactSchema> schemas)
            throws SQLException {
        var names = new ArrayList<String>();
        for (var schema : schemas) {
            String artifact = schema.artifactName();
            names.addAll(List.of(artifact, artifact + "_sources", artifact + "_history",
                    artifact + "_history_sources", artifact + "_receipt_rows"));
        }
        names.addAll(COMMON);
        if (new java.util.HashSet<>(names).size() != names.size()) {
            throw new SQLException("Canonical artifact and publication families overlap");
        }
        var tables = new ArrayList<JdbcPublicationTable>();
        for (String name : names) { tables.add(JdbcPublicationTable.inspect(connection, name)); }
        return List.copyOf(tables);
    }

    static void persist(Connection connection, List<JdbcPublicationTable> tables) throws SQLException {
        execute(connection, "CREATE TABLE publication_native_schema(name TEXT PRIMARY KEY,ddl TEXT NOT NULL)");
        execute(connection, "CREATE TABLE publication_native_column(table_name TEXT NOT NULL,ordinal INTEGER NOT NULL,"
                + "name TEXT NOT NULL,key_order INTEGER NOT NULL,PRIMARY KEY(table_name,ordinal)) WITHOUT ROWID");
        try (var shape = connection.prepareStatement("INSERT INTO publication_native_schema VALUES(?,?)");
             var field = connection.prepareStatement("INSERT INTO publication_native_column VALUES(?,?,?,?)")) {
            for (var table : tables) {
                shape.setString(1, table.name()); shape.setString(2, table.nativeDdl()); shape.executeUpdate();
                for (int index = 0; index < table.columns().size(); index++) {
                    String column = table.columns().get(index);
                    field.setString(1, table.name()); field.setInt(2, index); field.setString(3, column);
                    field.setInt(4, table.keys().indexOf(column) + 1); field.addBatch();
                }
                field.executeBatch(); field.clearBatch();
            }
        }
    }

    static List<JdbcPublicationTable> load(Connection connection) throws SQLException {
        var tables = new ArrayList<JdbcPublicationTable>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name,ddl FROM publication_native_schema ORDER BY name")) {
            while (rows.next()) {
                var columns = new ArrayList<String>();
                var keys = new java.util.TreeMap<Integer, String>();
                try (var fields = connection.prepareStatement(
                        "SELECT name,key_order FROM publication_native_column WHERE table_name=? ORDER BY ordinal")) {
                    fields.setString(1, rows.getString(1));
                    try (var info = fields.executeQuery()) {
                        while (info.next()) {
                            columns.add(info.getString(1));
                            if (info.getInt(2) > 0) { keys.put(info.getInt(2), info.getString(1)); }
                        }
                    }
                }
                tables.add(new JdbcPublicationTable(rows.getString(1), columns,
                        new ArrayList<>(keys.values()), rows.getString(2)));
            }
        }
        if (tables.isEmpty()) { throw new SQLException("Missing staged-publication catalog"); }
        return List.copyOf(tables);
    }

    /** Deliberately refuses existing business data; drained migration belongs to C6. */
    static void requireEmpty(Connection connection, List<JdbcPublicationTable> tables) throws SQLException {
        for (var table : tables) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT 1 FROM " + quote(table.name()) + " LIMIT 1")) {
                if (rows.next()) { throw new SQLException("Candidate initialization requires an empty native table: " + table.name()); }
            }
        }
    }
}
