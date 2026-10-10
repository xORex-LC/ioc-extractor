package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.adapter.out.store.jdbc.JdbcPublicationProtocol.Attempt;
import com.iocextractor.adapter.out.store.jdbc.JdbcPublicationProtocol.Fact;
import com.iocextractor.adapter.out.store.jdbc.JdbcPublicationProtocol.Manifest;
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
 * Test-only transport of a real fan-out import's typed effects.
 * Native import planning/mutation runs in the private database; this class
 * only stages its result and qualifies readers/receipt replay on another database.
 * Finite fixtures may contain a populated base. Whole-table fixture comparison
 * is deliberately not a production preparation algorithm or migration.
 */
final class SqliteImportPublicationExperiment {
    private static final int CHUNK_SIZE = 64;
    private static final String SCOPE = "typed-import";
    private final DataSource published;
    private final JdbcWriterAdmission admission;
    private final JdbcPublicationProtocol publication = new JdbcPublicationProtocol();
    private final Map<String, List<String>> columns = new LinkedHashMap<>();
    private final Map<String, List<String>> keys = new LinkedHashMap<>();
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
            tables.add(artifact + "_history");
            tables.add(artifact + "_history_sources");
            tables.add(artifact + "_receipt_rows");
        }
        tables.addAll(List.of("canonical_match_alias", "canonical_lifecycle_field_origin",
                "canonical_lifecycle_field_origin_history",
                "artifact_revision", "artifact_projection_state", "export_slot_assignment",
                "export_slot_state", "export_slot_free_range", "import_commit", "import_commit_artifact",
                "import_row_rejection", "import_row_warning", "import_slot_resolution",
                "canonical_observation", "canonical_observation_commit", "confirmation_receipt",
                "confirmation_receipt_artifact", "confirmation_receipt_field_position"));
        try (var c = published.getConnection()) {
            c.setAutoCommit(false);
            try {
                publication.initialize(c);
                for (String table : tables) { initializeTable(c, table); }
                seedLegacy(c);
                for (String table : tables.reversed()) { execute(c, "DROP TABLE " + quote(table)); }
                for (String table : tables) { createReadView(c, table); }
                c.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** Native preparation has completed; transport only changed typed addresses. */
    Attempt stage(DataSource working, String identity, String pins, IntConsumer afterChunk) throws SQLException {
        return stage(working, identity, pins, java.util.Set.of(), afterChunk);
    }

    Attempt stage(DataSource working, String identity, String pins, java.util.Set<String> renewals,
                  IntConsumer afterChunk) throws SQLException {
        try (var source = working.getConnection(); var base = published.getConnection()) {
            source.setAutoCommit(false);
            base.setAutoCommit(false);
            try {
                var changes = new ArrayList<TypedRow>();
                long minimumDeadline = Long.MAX_VALUE;
                for (String table : columns.keySet()) {
                    var previous = readRows(base, table);
                    for (var row : readRows(source, table).entrySet()) {
                        var old = previous.remove(row.getKey());
                        boolean renews = artifacts.contains(table) && renewals.contains(table + ":"
                                + row.getValue().get(columns.get(table).indexOf("id")));
                        if (row.getValue().equals(old) && !renews) { continue; }
                        changes.add(new TypedRow(table, row.getValue(), row.getKey(), false,
                                publicationColumns(table, row.getValue(), old, renews)));
                        if (artifacts.contains(table) && old != null) {
                            int lifecycle = columns.get(table).indexOf("_lifecycle_id");
                            if (old.get(lifecycle).equals(row.getValue().get(lifecycle))) {
                                int deadline = columns.get(table).indexOf("_valid_until_epoch_ms");
                                minimumDeadline = Math.min(minimumDeadline,
                                        ((Number) old.get(deadline)).longValue());
                            }
                        }
                    }
                    for (var deleted : previous.entrySet()) {
                        changes.add(new TypedRow(table, deleted.getValue(), deleted.getKey(), true, ""));
                    }
                }
                long epoch = scalar(base, "SELECT version FROM publication_epoch WHERE scope='" + SCOPE + "'");
                int count = changes.size();
                long deadline = minimumDeadline;
                Attempt attempt = write(c -> publication.begin(c, identity, pins,
                        new Manifest((count + CHUNK_SIZE - 1) / CHUNK_SIZE,
                                count, deadline, Map.of(SCOPE, epoch))));
                int ordinal = 0;
                for (int offset = 0; offset < changes.size(); offset += CHUNK_SIZE) {
                    append(attempt, ordinal++, changes.subList(offset, Math.min(offset + CHUNK_SIZE, changes.size())));
                    afterChunk.accept(ordinal);
                }
                write(c -> { publication.seal(c, attempt); return null; });
                source.rollback();
                base.rollback();
                return attempt;
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(source, failure);
                rollback(base, failure);
                throw failure;
            }
        }
    }

    private Map<String, List<Object>> readRows(Connection c, String table) throws SQLException {
        var result = new LinkedHashMap<String, List<Object>>();
        try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT " + joinedQuoted(columns.get(table)) + " FROM " + quote(table)
                        + " ORDER BY " + joinedQuoted(keys.get(table)))) {
            while (rows.next()) {
                var values = new ArrayList<Object>();
                for (int i = 1; i <= columns.get(table).size(); i++) { values.add(rows.getObject(i)); }
                var digest = new JdbcPublicationChunkDigest();
                for (String key : keys.get(table)) { digest.field(values.get(columns.get(table).indexOf(key))); }
                if (result.put(digest.finish(), Collections.unmodifiableList(values)) != null) {
                    throw new SQLException("Duplicate typed address in " + table);
                }
            }
        }
        return result;
    }

    private void seedLegacy(Connection c) throws SQLException {
        var legacy = publication.begin(c, "legacy-fixture", "legacy-fixture",
                new Manifest(0, 0, Long.MAX_VALUE, Map.of(SCOPE, 0L)));
        for (String table : columns.keySet()) {
            for (var row : readRows(c, table).entrySet()) {
                insert(c, legacy, new TypedRow(table, row.getValue(), row.getKey(), false, ""));
            }
        }
        publication.seal(c, legacy);
        publication.publish(c, legacy, 0, "legacy-fixture");
    }

    private String publicationColumns(String table, List<Object> values, List<Object> old, boolean renews) {
        var timed = new StringBuilder(",");
        for (int index = 0; index < columns.get(table).size(); index++) {
            String name = columns.get(table).get(index);
            boolean changed = old == null || !java.util.Objects.equals(values.get(index), old.get(index));
            if (renews && (name.equals("_last_confirmed_at_epoch_ms")
                    || name.equals("_valid_until_epoch_ms"))) { changed = true; }
            if (table.endsWith("_sources") && name.equals("last_seen_at") && old != null) {
                int occurrences = columns.get(table).indexOf("occurrences");
                changed |= !values.get(occurrences).equals(old.get(occurrences));
            }
            if (old != null && table.equals("artifact_revision") && name.equals("changed_at")) { changed = true; }
            if (old != null && table.equals("artifact_projection_state") && name.equals("requested_at_ms")) { changed = true; }
            if (old != null && table.equals("export_slot_state") && name.equals("updated_at_ms")) { changed = true; }
            if (changed) { timed.append(name).append(','); }
        }
        return timed.toString();
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) { throw new SQLException("Missing scalar result"); }
            return rows.getLong(1);
        }
    }

    JdbcPublicationProtocol.Receipt publish(Attempt attempt, JdbcLifecycleClock clock,
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
        var primary = new java.util.TreeMap<Integer, String>();
        try (var s = c.createStatement(); var rows = s.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (rows.next()) {
                if (rows.getInt("pk") > 0) { primary.put(rows.getInt("pk"), rows.getString("name")); }
            }
        }
        if (primary.isEmpty()) { throw new SQLException("Missing typed primary key: " + table); }
        keys.put(table, List.copyOf(primary.values()));
        columns.put(table, List.copyOf(names));
        execute(c, "CREATE TABLE " + quote(table + "_import_probe") + " AS SELECT "
                + joinedQuoted(names) + ",CAST(NULL AS INTEGER) AS unit_id,CAST(NULL AS INTEGER) AS fence,"
                + "CAST(NULL AS INTEGER) AS stamp,CAST(NULL AS TEXT) AS address,"
                + "CAST(NULL AS INTEGER) AS deleted,CAST(NULL AS TEXT) AS publication_columns,"
                + "CAST(NULL AS INTEGER) AS absolute_deadline,CAST(NULL AS INTEGER) AS deadline_anchor "
                + "FROM " + quote(table) + " WHERE 0");
        execute(c, "CREATE INDEX " + quote(table + "_import_probe_unit") + " ON "
                + quote(table + "_import_probe") + "(unit_id,fence)");
        execute(c, "CREATE UNIQUE INDEX " + quote(table + "_import_probe_address") + " ON "
                + quote(table + "_import_probe") + "(address,stamp DESC)");
        if (table.equals("canonical_match_alias")) {
            execute(c, "CREATE INDEX import_probe_alias_lookup ON canonical_match_alias_import_probe"
                    + "(artifact,definition_id,key_hash,key_canonical,canonical_row_id,lifecycle_id)");
        } else if (artifacts.contains(table)) {
            execute(c, "CREATE INDEX " + quote(table + "_import_probe_row") + " ON "
                    + quote(table + "_import_probe") + "(id)");
            execute(c, "CREATE INDEX " + quote(table + "_import_probe_absolute_deadline") + " ON "
                    + quote(table + "_import_probe") + "(absolute_deadline) WHERE absolute_deadline IS NOT NULL");
            execute(c, "CREATE INDEX " + quote(table + "_import_probe_deadline_anchor") + " ON "
                    + quote(table + "_import_probe") + "(deadline_anchor,address,stamp) WHERE deadline_anchor IS NOT NULL");
        } else if (names.contains("delivery_id")) {
            execute(c, "CREATE INDEX " + quote(table + "_import_probe_delivery") + " ON "
                    + quote(table + "_import_probe") + "(delivery_id)");
        }
    }

    private void createReadView(Connection c, String table) throws SQLException {
        var projection = columns.get(table).stream().map(name -> project(table, name))
                .collect(java.util.stream.Collectors.joining(","));
        execute(c, "CREATE VIEW " + quote(table) + " AS SELECT " + projection + " FROM "
                + quote(table + "_import_probe") + " v JOIN publication_unit u"
                + " ON u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED'"
                + " WHERE " + latest(table, "v"));
    }

    private String project(String table, String name) {
        if ((table.endsWith("_sources") && !table.endsWith("_history_sources")
                && (name.equals("first_seen_at") || name.equals("last_seen_at")))
                || (table.equals("artifact_revision") && name.equals("changed_at"))) {
            return timed(name, publicationIso());
        }
        if ((table.equals("artifact_projection_state") && name.equals("requested_at_ms"))
                || (table.equals("export_slot_assignment") && name.equals("assigned_at_ms"))
                || (table.equals("export_slot_state") && name.equals("updated_at_ms"))) {
            return timed(name, "u.effective_millis");
        }
        if (artifacts.contains(table)) {
            return switch (name) {
                case "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms" ->
                        timed(name, "u.effective_millis");
                case "_valid_until_epoch_ms" -> timed(name, "u.valid_until");
                case "_created_at" -> timed(name, publicationIso());
                default -> "v." + quote(name);
            };
        }
        if (table.equals("canonical_observation") && name.equals("started_at_ms")) {
            return timed(name, "u.effective_millis");
        }
        if (table.equals("confirmation_receipt_artifact") && name.equals("staged_at_ms")) {
            return timed(name, "u.effective_millis");
        }
        if (table.endsWith("_receipt_rows") && name.equals("observed_at_ms")) {
            return timed(name, "u.effective_millis");
        }
        if (table.equals("confirmation_receipt")) {
            if (name.equals("completed_at_ms")) { return timed(name, "u.effective_millis"); }
            if (name.equals("purge_after_ms")) {
                return timed(name, "u.effective_millis+(v.purge_after_ms-v.completed_at_ms)");
            }
        }
        if (table.equals("canonical_observation_commit") || table.equals("import_commit")) {
            return switch (name) {
                case "effective_as_of_ms", "committed_at_ms" -> timed(name, "u.effective_millis");
                case "purge_after_ms" -> timed(name, "u.effective_millis+(v.purge_after_ms-v.committed_at_ms)");
                default -> "v." + quote(name);
            };
        }
        return "v." + quote(name);
    }

    private static String timed(String name, String expression) {
        return "CASE WHEN v." + quote(name) + " IS NOT NULL AND instr(v.publication_columns,'," + name + ",')>0 THEN " + expression
                + " ELSE v." + quote(name) + " END AS " + quote(name);
    }

    private static String publicationIso() {
        return "CASE WHEN u.effective_millis%1000=0 THEN "
                + "strftime('%Y-%m-%dT%H:%M:%SZ',u.effective_millis/1000.0,'unixepoch') ELSE "
                + "strftime('%Y-%m-%dT%H:%M:%fZ',u.effective_millis/1000.0,'unixepoch') END";
    }

    List<String> tables() { return List.copyOf(columns.keySet()); }

    private static String latest(String table, String alias) {
        return alias + ".deleted=0 AND NOT EXISTS (SELECT 1 FROM " + quote(table + "_import_probe")
                + " newer WHERE newer.address=" + alias + ".address AND newer.stamp>" + alias + ".stamp"
                + " AND EXISTS (SELECT 1 FROM publication_unit nu WHERE nu.id=newer.unit_id"
                + " AND nu.fence=newer.fence AND nu.state='COMMITTED'))";
    }

    /** Separate ordered indexes handle inherited absolute values and publication anchors. */
    String nearestSql(String artifact) {
        if (!artifacts.contains(artifact)) { throw new IllegalArgumentException("Unknown artifact"); }
        String versions = quote(artifact + "_import_probe");
        return "SELECT MIN(deadline) FROM (SELECT * FROM (SELECT v.absolute_deadline AS deadline FROM "
                + versions + " v WHERE v.absolute_deadline>? AND EXISTS (SELECT 1 FROM publication_unit u"
                + " WHERE u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED') AND " + latest(artifact, "v")
                + " ORDER BY v.absolute_deadline LIMIT 1) UNION ALL SELECT * FROM ("
                + "SELECT anchor.valid_until AS deadline FROM publication_unit anchor WHERE anchor.state='COMMITTED'"
                + " AND anchor.valid_until>? AND EXISTS (SELECT 1 FROM " + versions
                + " v WHERE v.deadline_anchor=anchor.id AND EXISTS (SELECT 1 FROM publication_unit u"
                + " WHERE u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED') AND " + latest(artifact, "v")
                + ") ORDER BY anchor.valid_until LIMIT 1))";
    }

    java.util.OptionalLong nearest(String artifact, long asOf) throws SQLException {
        try (var c = published.getConnection(); var s = c.prepareStatement(nearestSql(artifact))) {
            s.setLong(1, asOf);
            s.setLong(2, asOf);
            try (var rows = s.executeQuery()) {
                if (!rows.next() || rows.getObject(1) == null) { return java.util.OptionalLong.empty(); }
                return java.util.OptionalLong.of(rows.getLong(1));
            }
        }
    }

    private void append(Attempt attempt, int ordinal, List<TypedRow> batch) {
        write(c -> {
            var digest = new JdbcPublicationChunkDigest();
            for (var row : batch) {
                digest.row();
                digest.field(row.table());
                for (String column : columns.get(row.table())) { digest.field(column); }
                for (Object value : row.values()) { digest.field(value); }
                digest.field(row.address());
                digest.field(row.deleted());
                digest.field(row.publicationColumns());
                digest.field(attempt.stamp());
                digest.field(attempt.unitId());
                digest.field(attempt.fence());
            }
            var facts = new ArrayList<Fact>(batch.size());
            String payload = digest.finish();
            for (int index = 0; index < batch.size(); index++) {
                var row = batch.get(index);
                Long anchor = artifacts.contains(row.table()) && !row.deleted()
                        && row.publicationColumns().contains(",_valid_until_epoch_ms,") ? attempt.unitId() : null;
                facts.add(new Fact("import-effect", SCOPE, row.table() + ":" + row.address(), payload, anchor));
            }
            if (publication.append(c, attempt, ordinal, facts)) {
                for (var row : batch) {
                    insert(c, attempt, row);
                }
            }
            return null;
        });
    }

    private void insert(Connection c, Attempt attempt, TypedRow row) throws SQLException {
        try (var statement = c.prepareStatement("INSERT INTO " + quote(row.table() + "_import_probe")
                + " (" + joinedQuoted(columns.get(row.table()))
                + ",unit_id,fence,stamp,address,deleted,publication_columns,absolute_deadline,deadline_anchor) VALUES ("
                + placeholders(row.values().size() + 8) + ")")) {
            int parameter = 1;
            for (Object value : row.values()) { statement.setObject(parameter++, value); }
            statement.setLong(parameter++, attempt.unitId());
            statement.setLong(parameter++, attempt.fence());
            statement.setLong(parameter++, attempt.stamp());
            statement.setString(parameter++, row.address());
            statement.setInt(parameter++, row.deleted() ? 1 : 0);
            statement.setString(parameter++, row.publicationColumns());
            boolean anchored = artifacts.contains(row.table())
                    && row.publicationColumns().contains(",_valid_until_epoch_ms,");
            Object absolute = artifacts.contains(row.table()) && !anchored
                    ? row.values().get(columns.get(row.table()).indexOf("_valid_until_epoch_ms")) : null;
            statement.setObject(parameter++, absolute);
            statement.setObject(parameter, anchored ? attempt.unitId() : null);
            statement.executeUpdate();
        }
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

    private record TypedRow(String table, List<Object> values, String address, boolean deleted,
                            String publicationColumns) { }
    @FunctionalInterface private interface Work<T> { T apply(Connection c) throws SQLException; }
}
