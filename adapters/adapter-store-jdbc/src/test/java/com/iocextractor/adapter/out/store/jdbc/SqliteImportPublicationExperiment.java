package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Attempt;
import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Fact;
import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Manifest;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.placeholders;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/**
 * Test-only transport of a real initial fan-out import's typed effects.
 * Native import planning/mutation runs in the private database; this class
 * only stages its result and qualifies readers/receipt replay on another database.
 * It is restricted to an empty canonical base, not a production writer or migration.
 */
final class SqliteImportPublicationExperiment {
    private static final int CHUNK_SIZE = 64;
    private static final String SCOPE = "initial-import";
    private final DataSource published;
    private final JdbcWriterAdmission admission;
    private final SqlitePublicationExperiment publication = new SqlitePublicationExperiment();
    private final Map<String, List<String>> columns = new LinkedHashMap<>();
    private final List<String> artifacts;

    SqliteImportPublicationExperiment(DataSource published, JdbcWriterAdmission admission,
                                      List<String> artifacts) throws SQLException {
        this.published = published;
        this.admission = admission;
        this.artifacts = List.copyOf(artifacts);
        var tables = new ArrayList<String>();
        for (String artifact : artifacts) {
            tables.add(artifact);
            tables.add(artifact + "_sources");
        }
        tables.addAll(List.of("canonical_match_alias", "canonical_lifecycle_field_origin",
                "artifact_revision", "artifact_projection_state", "export_slot_assignment",
                "export_slot_state", "export_slot_free_range", "import_commit", "import_commit_artifact",
                "import_row_rejection", "import_row_warning", "import_slot_resolution"));
        try (var c = published.getConnection()) {
            c.setAutoCommit(false);
            try {
                publication.initialize(c);
                for (String table : tables) { initializeTable(c, table); }
                for (String table : tables.reversed()) { execute(c, "DROP TABLE " + quote(table)); }
                for (String table : tables) { createReadView(c, table); }
                c.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** The caller has already run the real writer against a fresh private database. */
    Attempt stage(DataSource working, String identity, String pins, IntConsumer afterChunk) throws SQLException {
        try (var source = working.getConnection()) {
            source.setAutoCommit(false);
            try {
                int count = 0;
                for (String table : columns.keySet()) {
                    try (var s = source.createStatement();
                         var rows = s.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
                        if (!rows.next()) { throw new SQLException("Missing import effect count"); }
                        count = Math.addExact(count, rows.getInt(1));
                    }
                }
                int contributionCount = count;
                Attempt attempt = write(c -> publication.begin(c, identity, pins,
                        new Manifest((contributionCount + CHUNK_SIZE - 1) / CHUNK_SIZE,
                                contributionCount, Long.MAX_VALUE, Map.of(SCOPE, 0L))));
                int ordinal = 0;
                var batch = new ArrayList<TypedRow>(CHUNK_SIZE);
                for (var table : columns.entrySet()) {
                    try (var select = source.createStatement(); var rows = select.executeQuery(
                            "SELECT " + joinedQuoted(table.getValue()) + " FROM " + quote(table.getKey())
                                    + " ORDER BY " + joinedQuoted(table.getValue()))) {
                        while (rows.next()) {
                            var values = new ArrayList<Object>(table.getValue().size());
                            for (int i = 1; i <= table.getValue().size(); i++) { values.add(rows.getObject(i)); }
                            batch.add(new TypedRow(table.getKey(), Collections.unmodifiableList(values)));
                            if (batch.size() == CHUNK_SIZE) {
                                append(attempt, ordinal++, batch);
                                batch.clear();
                                afterChunk.accept(ordinal);
                            }
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    append(attempt, ordinal++, batch);
                    afterChunk.accept(ordinal);
                }
                write(c -> { publication.seal(c, attempt); return null; });
                source.rollback();
                return attempt;
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(source, failure);
                throw failure;
            }
        }
    }

    SqlitePublicationExperiment.Receipt publish(Attempt attempt, JdbcLifecycleClock clock,
                                              FixedRecordValidityPolicy validity, String pins) {
        return write(c -> {
            var committed = publication.receipt(c, attempt.unitId());
            long utc = committed.isPresent() ? committed.orElseThrow().effectiveMillis()
                    : clock.now(c).value().toEpochMilli();
            return publication.publish(c, attempt, utc, pins, validity);
        });
    }

    private void initializeTable(Connection c, String table) throws SQLException {
        var names = new ArrayList<String>();
        try (var s = c.createStatement(); var rows = s.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (rows.next()) { names.add(rows.getString("name")); }
        }
        if (names.isEmpty()) { throw new SQLException("Missing typed import table: " + table); }
        if (artifacts.contains(table)) {
            try (var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
                if (!rows.next() || rows.getLong(1) != 0) {
                    throw new SQLException("Initial import probe requires an empty canonical base");
                }
            }
        }
        columns.put(table, List.copyOf(names));
        execute(c, "CREATE TABLE " + quote(table + "_import_probe") + " AS SELECT "
                + joinedQuoted(names) + ",CAST(NULL AS INTEGER) AS unit_id,CAST(NULL AS INTEGER) AS fence "
                + "FROM " + quote(table) + " WHERE 0");
        execute(c, "CREATE INDEX " + quote(table + "_import_probe_unit") + " ON "
                + quote(table + "_import_probe") + "(unit_id,fence)");
        if (table.equals("canonical_match_alias")) {
            execute(c, "CREATE INDEX import_probe_alias_lookup ON canonical_match_alias_import_probe"
                    + "(artifact,definition_id,key_hash,key_canonical,canonical_row_id,lifecycle_id)");
        } else if (artifacts.contains(table)) {
            execute(c, "CREATE INDEX " + quote(table + "_import_probe_row") + " ON "
                    + quote(table + "_import_probe") + "(id)");
        } else if (names.contains("delivery_id")) {
            execute(c, "CREATE INDEX " + quote(table + "_import_probe_delivery") + " ON "
                    + quote(table + "_import_probe") + "(delivery_id)");
        }
    }

    private void createReadView(Connection c, String table) throws SQLException {
        var projection = columns.get(table).stream().map(name -> project(table, name))
                .collect(java.util.stream.Collectors.joining(","));
        execute(c, "CREATE VIEW " + quote(table) + " AS SELECT " + projection + " FROM "
                + quote(table + "_import_probe") + " v JOIN experiment_unit u"
                + " ON u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED'");
    }

    private String project(String table, String name) {
        if ((table.endsWith("_sources") && (name.equals("first_seen_at") || name.equals("last_seen_at")))
                || (table.equals("artifact_revision") && name.equals("changed_at"))) {
            return publicationIso() + " AS " + quote(name);
        }
        if ((table.equals("artifact_projection_state") && name.equals("requested_at_ms"))
                || (table.equals("export_slot_assignment") && name.equals("assigned_at_ms"))
                || (table.equals("export_slot_state") && name.equals("updated_at_ms"))) {
            return "u.effective_millis AS " + quote(name);
        }
        if (artifacts.contains(table)) {
            return switch (name) {
                case "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms" ->
                        "u.effective_millis AS " + quote(name);
                case "_valid_until_epoch_ms" -> "u.valid_until AS " + quote(name);
                case "_created_at" -> publicationIso() + " AS " + quote(name);
                default -> "v." + quote(name);
            };
        }
        if (table.equals("import_commit")) {
            return switch (name) {
                case "effective_as_of_ms", "committed_at_ms" -> "u.effective_millis AS " + quote(name);
                case "purge_after_ms" -> "u.effective_millis+(v.purge_after_ms-v.committed_at_ms) AS " + quote(name);
                default -> "v." + quote(name);
            };
        }
        return "v." + quote(name);
    }

    private static String publicationIso() {
        return "CASE WHEN u.effective_millis%1000=0 THEN "
                + "strftime('%Y-%m-%dT%H:%M:%SZ',u.effective_millis/1000.0,'unixepoch') ELSE "
                + "strftime('%Y-%m-%dT%H:%M:%fZ',u.effective_millis/1000.0,'unixepoch') END";
    }

    List<String> tables() { return List.copyOf(columns.keySet()); }

    private void append(Attempt attempt, int ordinal, List<TypedRow> batch) {
        write(c -> {
            var digest = new SqlitePublicationChunkDigest();
            for (var row : batch) {
                digest.row();
                digest.field(row.table());
                for (String column : columns.get(row.table())) { digest.field(column); }
                for (Object value : row.values()) { digest.field(value); }
                digest.field(attempt.unitId());
                digest.field(attempt.fence());
            }
            var facts = new ArrayList<Fact>(batch.size());
            String payload = digest.finish();
            for (int index = 0; index < batch.size(); index++) {
                facts.add(new Fact("import-effect", SCOPE, ordinal + ":" + index, payload, attempt.unitId()));
            }
            if (publication.append(c, attempt, ordinal, facts)) {
                for (var row : batch) {
                    try (var insert = c.prepareStatement("INSERT INTO " + quote(row.table() + "_import_probe")
                            + " (" + joinedQuoted(columns.get(row.table())) + ",unit_id,fence) VALUES ("
                            + placeholders(row.values().size() + 2) + ")")) {
                        int parameter = 1;
                        for (Object value : row.values()) { insert.setObject(parameter++, value); }
                        insert.setLong(parameter++, attempt.unitId());
                        insert.setLong(parameter, attempt.fence());
                        insert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    private <T> T write(Work<T> work) {
        return admission.execute(JdbcWriterAdmission.OperationClass.PROMOTION, () -> {
            try (var c = published.getConnection()) {
                c.setAutoCommit(false);
                try {
                    T result = work.apply(c);
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException | Error failure) {
                    rollback(c, failure);
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IocExtractorException("Typed import publication experiment failed", failure);
            }
        });
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement()) { statement.execute(sql); }
    }

    private static void rollback(Connection c, Throwable failure) {
        try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
    }

    private record TypedRow(String table, List<Object> values) { }
    @FunctionalInterface private interface Work<T> { T apply(Connection c) throws SQLException; }
}
