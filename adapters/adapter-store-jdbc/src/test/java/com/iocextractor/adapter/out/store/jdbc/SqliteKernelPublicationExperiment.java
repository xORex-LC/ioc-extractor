package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Attempt;
import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Fact;
import com.iocextractor.adapter.out.store.jdbc.SqlitePublicationExperiment.Manifest;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.CanonicalMatchPlan;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleId;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/**
 * Test-only bridge: the existing kernel mutates a selective private SQLite working set.
 * Typed row/alias/source/origin snapshots become visible through one publication header.
 * This fixed-schema probe is neither a production schema nor a complete canonical writer.
 */
final class SqliteKernelPublicationExperiment implements AutoCloseable {
    static final DataframeArtifactSchema SCHEMA = new DataframeArtifactSchema("masks", List.of(
            new DataframeColumn("id"), new DataframeColumn("mask"),
            new DataframeColumn("name"), new DataframeColumn("source")));
    static final ArtifactIdentityDefinition IDENTITY = new ArtifactIdentityDefinition("masks",
            new CanonicalKeyDefinition("row-v1", CanonicalKeyMode.COMPOSITE, List.of("mask")),
            List.of(new CanonicalKeyDefinition("match-v1", CanonicalKeyMode.COMPOSITE, List.of("mask"))), 1);
    private static final List<String> TABLES = List.of("masks", "masks_sources",
            "canonical_match_alias", "canonical_lifecycle_field_origin", "masks_history",
            "masks_history_sources", "canonical_lifecycle_field_origin_history");
    private static final String PINS = "kernel-probe/schema:12/identity:row-v1/ttl:1h";
    final HikariDataSource published;
    final HikariDataSource working;
    final MutableClock wallClock;
    final JdbcWriterAdmission admission = new JdbcWriterAdmission();
    private final SqlitePublicationExperiment probe = new SqlitePublicationExperiment();
    private final FixedRecordValidityPolicy validity = new FixedRecordValidityPolicy(Duration.ofHours(1));
    private final CanonicalArtifactKeyResolver keys = new CanonicalArtifactKeyResolver(List.of(IDENTITY));
    private final JdbcCanonicalMutationEngine kernel;
    private final JdbcLifecycleClock safeClock;
    private final JdbcArtifactIdAllocator rowIds;
    private final JdbcLifecycleIdAllocator lifecycleIds;
    private final SqliteInternalIdReservationExperiment historyIds;
    private final Map<String, List<String>> columns = new LinkedHashMap<>();

    SqliteKernelPublicationExperiment(Path directory, Instant start) throws SQLException {
        wallClock = new MutableClock(start);
        published = database(directory.resolve("kernel-publication.db"));
        HikariDataSource privateDatabase = null;
        try {
            privateDatabase = database(directory.resolve("kernel-working.db"));
            working = privateDatabase;
            initialize(working);
            initialize(published);
            kernel = new JdbcCanonicalMutationEngine(working, List.of(SCHEMA), List.of(IDENTITY));
            safeClock = new JdbcLifecycleClock(published, wallClock,
                    new LifecycleClockPolicy(Duration.ofSeconds(5), Duration.ofMinutes(1)), admission);
            rowIds = new JdbcArtifactIdAllocator(published, wallClock);
            rowIds.ensureInitialized(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 11, 1));
            lifecycleIds = new JdbcLifecycleIdAllocator(published, wallClock);
            historyIds = new SqliteInternalIdReservationExperiment(published, wallClock, admission);
            historyIds.initializeHistory("masks", 1);
            try (var c = published.getConnection()) {
                probe.initialize(c);
                initializeVersions(c);
            }
        } catch (SQLException | RuntimeException | Error failure) {
            if (privateDatabase != null) { privateDatabase.close(); }
            published.close();
            throw failure;
        }
    }

    RegisteredObservation register(String occurrence) {
        return register(occurrence, ObservationOrigin.DOCUMENT);
    }

    RegisteredObservation register(String occurrence, ObservationOrigin origin) {
        return new JdbcObservationRegistrationStore(published, wallClock, admission)
                .registerNew(new ObservationId(occurrence), origin);
    }

    /** Hydrates only the requested record and its children; never clones a whole artifact. */
    void hydrate(Connection pinnedBase, String mask, RegisteredObservation registration) throws SQLException {
        try (var c = working.getConnection()) {
            c.setAutoCommit(false);
            try {
                // A fresh working set is used for each experiment preparation invocation.
                for (String table : TABLES.reversed()) {
                    execute(c, "DELETE FROM " + quote(table));
                }
                copyRegistration(pinnedBase, c, registration);
                copy(pinnedBase, c, "masks", "row_key = ?", recordKey(mask), null, null);
                String selectedId = "(SELECT id FROM masks WHERE row_key = ?)";
                copy(pinnedBase, c, "masks_sources", "row_id = " + selectedId, recordKey(mask), null, null);
                copy(pinnedBase, c, "canonical_match_alias", "artifact='masks' AND canonical_row_id = "
                        + selectedId, recordKey(mask), null, null);
                copy(pinnedBase, c, "canonical_lifecycle_field_origin",
                        "artifact='masks' AND lifecycle_id = (SELECT _lifecycle_id FROM masks WHERE row_key = ?)",
                        recordKey(mask), null, null);
                c.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    CanonicalRecordMutationOutcome confirm(String mask, String name, String source,
                                           long reservedId, long reservedLifecycle,
                                           RegisteredObservation registration, long position) throws SQLException {
        try (var c = working.getConnection()) {
            c.setAutoCommit(false);
            try {
                kernel.validateRegistration(c, registration);
                var time = EffectiveTime.at(wallClock.instant());
                var row = row(mask, name, source);
                var prepared = new PreparedArtifactRow(row, Optional.of("id"),
                        Map.of("name", new OccurrencePosition(position)));
                CanonicalRecordMutationOutcome result;
                try (var session = kernel.openSession(c, SCHEMA, time, validity.decide(time))) {
                    result = session.confirm(source,
                            new CanonicalRecordConfirmation(new ArtifactRowKey(recordKey(mask)), prepared),
                            reservedId, new LifecycleId(reservedLifecycle), registration);
                }
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** Real dataframe-owned reservations commit before private kernel work and are never returned. */
    CanonicalRecordMutationOutcome confirmReserved(String mask, String name, String source,
                                                   RegisteredObservation registration, long position) throws SQLException {
        var publicRange = admission.execute(JdbcWriterAdmission.OperationClass.CONTROL,
                () -> rowIds.reserve("masks", 1));
        var lifecycleRange = admission.execute(JdbcWriterAdmission.OperationClass.CONTROL,
                () -> lifecycleIds.reserve(1));
        var historyRange = historyIds.reserve("masks_history", 1);
        try (var c = working.getConnection()) {
            c.setAutoCommit(false);
            try {
                SqliteInternalIdReservationExperiment.seedPrivateSequence(c, "masks_history", historyRange);
                c.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(c, failure);
                throw failure;
            }
        }
        return confirm(mask, name, source, publicRange.idAt(0), lifecycleRange.idAt(0).value(), registration, position);
    }

    /** Applies the existing import kernel's ordered patch; merge planning remains outside this probe. */
    CanonicalRecordMutationOutcome patch(long rowId, String mask, String name, String source,
                                         RegisteredObservation registration) throws SQLException {
        try (var c = working.getConnection()) {
            c.setAutoCommit(false);
            try {
                kernel.validateRegistration(c, registration);
                var time = EffectiveTime.at(wallClock.instant());
                var row = row(mask, name, source).withValue("id", Long.toString(rowId));
                var prepared = new PreparedArtifactRow(row, Optional.empty(),
                        Map.of("name", new OccurrencePosition(0)));
                CanonicalRecordMutationOutcome result;
                try (var session = kernel.openSession(c, SCHEMA, time, validity.decide(time))) {
                    result = session.mutateExistingOrdered(rowId, row, prepared, false, source, registration);
                }
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** One bounded typed snapshot; publication does not replay the kernel or copy N rows. */
    Attempt stage(String unit, String mask, long epoch, long minimumDeadline) {
        return stage(unit, null, mask, epoch, minimumDeadline, true);
    }

    Attempt restage(Attempt previous, String mask, long epoch, long minimumDeadline) {
        return stage(null, previous, mask, epoch, minimumDeadline, true);
    }

    Attempt stagePatch(String unit, String mask, long epoch) {
        return stage(unit, null, mask, epoch, Long.MAX_VALUE, false);
    }

    /** Streams a complete typed working set through independently committed bounded chunks. */
    Attempt stageChunks(String unit, String mask, long epoch, BiConsumer<Attempt, Integer> afterChunk) throws SQLException {
        try (var source = working.getConnection()) {
            source.setAutoCommit(false);
            try {
                try (var s = source.createStatement();
                     var rows = s.executeQuery("SELECT COUNT(*),MIN(row_key) FROM masks")) {
                    if (!rows.next() || rows.getLong(1) != 1 || !recordKey(mask).equals(rows.getString(2))) {
                        throw new SQLException("Typed probe requires exactly its requested working row");
                    }
                }
                long rowCount = 0;
                for (String table : TABLES) {
                    try (var s = source.createStatement();
                         var rows = s.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
                        if (!rows.next()) { throw new SQLException("Missing typed row count"); }
                        rowCount = Math.addExact(rowCount, rows.getLong(1));
                    }
                }
                int contributions = Math.toIntExact(Math.addExact(rowCount, 1));
                int chunks = Math.toIntExact((rowCount + 255) / 256 + 1);
                Attempt attempt = write(c -> probe.begin(c, unit, PINS,
                        new Manifest(chunks, contributions, Long.MAX_VALUE, Map.of("masks", epoch))));
                var batch = new ArrayList<TypedRow>(256);
                int ordinal = 0;
                for (String table : TABLES) {
                    String projection = JdbcSql.joinedQuoted(columns.get(table));
                    try (var select = source.createStatement();
                         var rows = select.executeQuery("SELECT " + projection + " FROM " + quote(table)
                                 + " ORDER BY " + projection)) {
                        while (rows.next()) {
                            var values = new ArrayList<Object>(columns.get(table).size());
                            for (int index = 1; index <= columns.get(table).size(); index++) {
                                values.add(rows.getObject(index));
                            }
                            batch.add(new TypedRow(table, java.util.Collections.unmodifiableList(values)));
                            if (batch.size() == 256) {
                                appendTypedChunk(attempt, ordinal++, batch);
                                batch.clear();
                                afterChunk.accept(attempt, ordinal);
                            }
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    appendTypedChunk(attempt, ordinal++, batch);
                    afterChunk.accept(attempt, ordinal);
                }
                int finalOrdinal = ordinal;
                write(c -> {
                    probe.append(c, attempt, finalOrdinal, List.of(new Fact("row", "masks", recordKey(mask),
                            "typed-chunks:" + contributions, attempt.unitId())));
                    probe.seal(c, attempt);
                    return null;
                });
                source.rollback();
                return attempt;
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(source, failure);
                throw failure;
            }
        }
    }

    private void appendTypedChunk(Attempt attempt, int ordinal, List<TypedRow> batch) {
        write(c -> {
            var digest = new SqlitePublicationChunkDigest();
            var facts = new ArrayList<Fact>(batch.size());
            var versions = new ArrayList<PendingTypedRow>(batch.size());
            for (int index = 0; index < batch.size(); index++) {
                TypedRow row = batch.get(index);
                List<String> names = columns.get(row.table());
                var values = new ArrayList<>(row.values());
                values.add(attempt.unitId());
                values.add(attempt.fence());
                String anchors = "";
                if (row.table().equals("masks")) {
                    anchors = ",first_anchor,renewal_anchor";
                    values.add(firstAnchor(c, ((Number) row.values().get(names.indexOf("id"))).longValue(),
                            attempt.unitId()));
                    values.add(attempt.unitId());
                } else if (row.table().equals("masks_sources")) {
                    anchors = ",first_anchor,last_anchor";
                    var sourceAnchors = sourceAnchors(c,
                            ((Number) row.values().get(names.indexOf("row_id"))).longValue(),
                            (String) row.values().get(names.indexOf("source_key")),
                            ((Number) row.values().get(names.indexOf("occurrences"))).longValue(), attempt.unitId());
                    values.add(sourceAnchors[0]);
                    values.add(sourceAnchors[1]);
                }
                digest.row();
                digest.field(row.table());
                for (Object value : values) { digest.field(value); }
                versions.add(new PendingTypedRow(row.table(), anchors,
                        java.util.Collections.unmodifiableList(values)));
                facts.add(new Fact("typed", "masks", ordinal + ":" + index, row.table()));
            }
            // The chunk journal covers typed bytes as well as its logical pointers.
            String payload = digest.finish();
            facts.replaceAll(fact -> new Fact(fact.family(), fact.scope(), fact.key(), payload));
            if (probe.append(c, attempt, ordinal, facts)) {
                for (var version : versions) {
                    try (var insert = c.prepareStatement("INSERT INTO " + quote(version.table() + "_probe_version")
                            + " (" + JdbcSql.joinedQuoted(columns.get(version.table())) + ",unit_id,fence"
                            + version.anchors() + ") VALUES (" + JdbcSql.placeholders(version.values().size()) + ")")) {
                        for (int parameter = 0; parameter < version.values().size(); parameter++) {
                            insert.setObject(parameter + 1, version.values().get(parameter));
                        }
                        insert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    /** Replays one acknowledged chunk from the retained private working set, under its current fence. */
    void retryChunk(Attempt attempt, int ordinal) throws SQLException {
        var batch = new ArrayList<TypedRow>(256);
        long start = Math.multiplyExact((long) ordinal, 256);
        long position = 0;
        try (var c = working.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (String table : TABLES) {
                    String projection = JdbcSql.joinedQuoted(columns.get(table));
                    try (var s = c.createStatement(); var rows = s.executeQuery("SELECT " + projection
                            + " FROM " + quote(table) + " ORDER BY " + projection)) {
                        while (rows.next() && batch.size() < 256) {
                            if (position++ < start) { continue; }
                            var values = new ArrayList<Object>();
                            for (int index = 1; index <= columns.get(table).size(); index++) {
                                values.add(rows.getObject(index));
                            }
                            batch.add(new TypedRow(table, java.util.Collections.unmodifiableList(values)));
                        }
                    }
                    if (batch.size() == 256) { break; }
                }
                c.rollback();
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(c, failure);
                throw failure;
            }
        }
        appendTypedChunk(attempt, ordinal, batch);
    }

    private record TypedRow(String table, List<Object> values) { }
    private record PendingTypedRow(String table, String anchors, List<Object> values) { }

    private Attempt stage(String unit, Attempt previous, String mask, long epoch, long minimumDeadline,
                           boolean renewTtl) {
        return write(c -> {
            var manifest = new Manifest(1, 1, minimumDeadline, Map.of("masks", epoch));
            Attempt attempt = previous == null ? probe.begin(c, unit, PINS, manifest)
                    : probe.rebuild(c, previous, PINS, manifest);
            var digest = new SqlitePublicationChunkDigest();
            try (var source = working.getConnection()) {
                source.setAutoCommit(false);
                try {
                    try (var check = source.prepareStatement("SELECT COUNT(*),MIN(row_key) FROM masks");
                         var rows = check.executeQuery()) {
                        if (!rows.next() || rows.getInt(1) != 1 || !recordKey(mask).equals(rows.getString(2))) {
                            throw new SQLException("Typed probe requires exactly its requested working row");
                        }
                    }
                    for (String table : TABLES) {
                        copy(source, c, table, "1=1", null, attempt, digest, renewTtl);
                    }
                    source.rollback();
                } catch (SQLException | RuntimeException | Error failure) {
                    rollback(source, failure);
                    throw failure;
                }
            }
            Long deadlineAnchor = renewTtl ? attempt.unitId() : renewalAnchor(c, recordKey(mask));
            probe.append(c, attempt, 0, List.of(new Fact("row", "masks", recordKey(mask),
                    digest.finish(), deadlineAnchor)));
            probe.seal(c, attempt);
            return attempt;
        });
    }

    SqlitePublicationExperiment.Receipt publish(Attempt attempt) {
        return write(c -> {
            var receipt = probe.receipt(c, attempt.unitId());
            long effectiveMillis = receipt.isPresent() ? receipt.orElseThrow().effectiveMillis()
                    : safeClock.now(c).value().toEpochMilli();
            return probe.publish(c, attempt, effectiveMillis,
                    "kernel-receipt:" + attempt.unitId(), validity);
        });
    }

    CanonicalMatchPlan match(Connection c, String mask, Instant asOf) throws SQLException {
        var request = new CanonicalMatchRequest("probe", keys.matchKeysOf("masks", row(mask, null, null)));
        return new JdbcCanonicalMatchPlanner(published, List.of(SCHEMA))
                .plan(c, SCHEMA, EffectiveTime.at(asOf), List.of(request)).getFirst();
    }

    private void initialize(HikariDataSource database) {
        new SqliteUserVersionSchemaMigrator(database, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(database).reconcile(List.of(SCHEMA));
        new JdbcArtifactIdentityStore(database, wallClock).ensure(IDENTITY);
    }

    private void initializeVersions(Connection c) throws SQLException {
        for (String table : TABLES) {
            var names = new ArrayList<String>();
            try (var s = c.createStatement(); var rows = s.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
                while (rows.next()) { names.add(rows.getString("name")); }
            }
            columns.put(table, List.copyOf(names));
            execute(c, "CREATE TABLE " + quote(table + "_probe_version") + " AS SELECT *, "
                    + "CAST(NULL AS INTEGER) AS unit_id, CAST(NULL AS INTEGER) AS fence "
                    + "FROM " + quote(table) + " WHERE 0");
            execute(c, "CREATE INDEX " + quote(table + "_probe_unit") + " ON "
                    + quote(table + "_probe_version") + "(unit_id,fence)");
            if (table.equals("masks")) {
                execute(c, "ALTER TABLE masks_probe_version ADD COLUMN first_anchor INTEGER");
                execute(c, "ALTER TABLE masks_probe_version ADD COLUMN renewal_anchor INTEGER");
            } else if (table.equals("masks_sources")) {
                execute(c, "ALTER TABLE masks_sources_probe_version ADD COLUMN first_anchor INTEGER");
                execute(c, "ALTER TABLE masks_sources_probe_version ADD COLUMN last_anchor INTEGER");
            }
        }
        execute(c, "CREATE INDEX masks_probe_key ON masks_probe_version(row_key,unit_id,fence)");
        execute(c, "CREATE INDEX masks_probe_id ON masks_probe_version(id,unit_id,fence)");
        execute(c, "CREATE INDEX canonical_match_alias_probe_lookup ON canonical_match_alias_probe_version"
                + "(artifact,definition_id,key_hash,key_canonical,canonical_row_id,lifecycle_id,unit_id,fence)");
        for (String table : TABLES.reversed()) { execute(c, "DROP TABLE " + quote(table)); }
        // Read compatibility views only. Native kernel SQL runs against real private tables.
        execute(c, """
                CREATE VIEW masks AS
                SELECT v.id,v.mask,v.name,v.source,v.row_key,
                       CASE WHEN first.id IS NULL THEN v._created_at
                         ELSE strftime('%Y-%m-%dT%H:%M:%fZ',first.effective_millis/1000.0,'unixepoch') END AS _created_at,
                       v._first_source_key,v._lifecycle_id,
                       COALESCE(first.effective_millis,v._first_confirmed_at_epoch_ms) AS _first_confirmed_at_epoch_ms,
                       COALESCE(renewal.effective_millis,v._last_confirmed_at_epoch_ms) AS _last_confirmed_at_epoch_ms,
                       COALESCE(renewal.valid_until,v._valid_until_epoch_ms) AS _valid_until_epoch_ms,
                       v.unit_id AS _probe_unit, v.fence AS _probe_fence,
                       v.first_anchor AS _probe_first_anchor,v.renewal_anchor AS _probe_renewal_anchor
                FROM masks_probe_version v
                JOIN experiment_unit u ON u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED'
                LEFT JOIN experiment_unit first ON first.id=v.first_anchor AND first.state='COMMITTED'
                LEFT JOIN experiment_unit renewal ON renewal.id=v.renewal_anchor AND renewal.state='COMMITTED'
                JOIN experiment_fact f ON f.unit_id=v.unit_id AND f.fence=v.fence
                  AND f.family='row' AND f.scope='masks' AND f.fact_key=v.row_key AND f.payload IS NOT NULL
                WHERE NOT EXISTS (
                  SELECT 1 FROM experiment_fact newer
                  JOIN experiment_unit n ON n.id=newer.unit_id AND n.fence=newer.fence AND n.state='COMMITTED'
                  WHERE newer.family='row' AND newer.scope='masks' AND newer.fact_key=f.fact_key
                    AND newer.stamp>f.stamp)
                """);
        for (String table : TABLES.subList(1, 4)) {
            String rowJoin = switch (table) {
                case "masks_sources" -> "v.row_id=r.id";
                case "canonical_match_alias" -> "v.artifact='masks' AND v.canonical_row_id=r.id AND v.lifecycle_id=r._lifecycle_id";
                default -> "v.artifact='masks' AND v.lifecycle_id=r._lifecycle_id";
            };
            boolean sources = table.equals("masks_sources");
            String projection = columns.get(table).stream().map(name -> {
                if (sources && name.equals("first_seen_at")) {
                    return "COALESCE(" + anchorIso("first") + ",v.first_seen_at) AS first_seen_at";
                }
                if (sources && name.equals("last_seen_at")) {
                    return "COALESCE(" + anchorIso("last") + ",v.last_seen_at) AS last_seen_at";
                }
                return "v." + quote(name);
            })
                    .collect(java.util.stream.Collectors.joining(","));
            if (sources) {
                projection += ",v.first_anchor AS _probe_first_anchor,v.last_anchor AS _probe_last_anchor";
            }
            execute(c, "CREATE VIEW " + quote(table) + " AS SELECT " + projection + " FROM "
                    + quote(table + "_probe_version") + " v JOIN masks r ON " + rowJoin
                    + " AND v.unit_id=r._probe_unit AND v.fence=r._probe_fence"
                    + (sources ? " LEFT JOIN experiment_unit first ON first.id=v.first_anchor AND first.state='COMMITTED'"
                    + " LEFT JOIN experiment_unit last ON last.id=v.last_anchor AND last.state='COMMITTED'" : ""));
        }
        for (String table : TABLES.subList(4, TABLES.size())) {
            String projection = columns.get(table).stream().map(name -> switch (name) {
                case "closed_at_epoch_ms", "archived_at_ms" -> "u.effective_millis AS " + quote(name);
                default -> "v." + quote(name);
            }).collect(java.util.stream.Collectors.joining(","));
            execute(c, "CREATE VIEW " + quote(table) + " AS SELECT " + projection + " FROM "
                    + quote(table + "_probe_version") + " v JOIN experiment_unit u"
                    + " ON u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED'");
        }
    }

    private void copy(Connection source, Connection destination, String table, String predicate,
                      String parameter, Attempt version, SqlitePublicationChunkDigest digest) throws SQLException {
        copy(source, destination, table, predicate, parameter, version, digest, true);
    }

    private void copy(Connection source, Connection destination, String table, String predicate,
                      String parameter, Attempt version, SqlitePublicationChunkDigest digest, boolean renewTtl) throws SQLException {
        List<String> names = columns.getOrDefault(table, List.of());
        if (names.isEmpty()) { throw new SQLException("Missing experiment column catalog"); }
        String projection = JdbcSql.joinedQuoted(names);
        String target = table + (version == null ? "" : "_probe_version");
        boolean rowAnchor = version != null && table.equals("masks");
        boolean sourceAnchors = version != null && table.equals("masks_sources");
        String insertColumns = projection + (version == null ? "" : ",unit_id,fence")
                + (rowAnchor ? ",first_anchor,renewal_anchor" : "") + (sourceAnchors ? ",first_anchor,last_anchor" : "");
        if (digest != null) {
            digest.field(table);
            digest.field(insertColumns);
        }
        try (var select = source.prepareStatement("SELECT " + projection + " FROM " + quote(table)
                + " WHERE " + predicate + " ORDER BY " + projection + (version == null ? "" : " LIMIT 257"));
             var insert = destination.prepareStatement("INSERT INTO " + quote(target) + " (" + insertColumns
                     + ") VALUES (" + JdbcSql.placeholders(names.size() + (version == null ? 0 : 2)
                     + (rowAnchor ? 2 : 0) + (sourceAnchors ? 2 : 0)) + ")")) {
            if (parameter != null) { select.setString(1, parameter); }
            try (var rows = select.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 256 && version != null) {
                        throw new SQLException("Typed experiment snapshot exceeds bound");
                    }
                    if (digest != null) { digest.row(); }
                    for (int index = 1; index <= names.size(); index++) {
                        Object value = rows.getObject(index);
                        insert.setObject(index, value);
                        if (digest != null) { digest.field(value); }
                    }
                    if (version != null) {
                        insert.setLong(names.size() + 1, version.unitId());
                        insert.setLong(names.size() + 2, version.fence());
                        digest.field(version.unitId());
                        digest.field(version.fence());
                        if (rowAnchor) {
                            Long anchor = firstAnchor(destination, rows.getLong("id"), version.unitId());
                            insert.setObject(names.size() + 3, anchor);
                            digest.field(anchor);
                            Long renewal = renewTtl ? version.unitId()
                                    : renewalAnchor(destination, rows.getString("row_key"));
                            insert.setObject(names.size() + 4, renewal);
                            digest.field(renewal);
                        }
                        if (sourceAnchors) {
                            var anchors = sourceAnchors(destination, rows.getLong("row_id"),
                                    rows.getString("source_key"), rows.getLong("occurrences"), version.unitId());
                            insert.setObject(names.size() + 3, anchors[0]);
                            insert.setObject(names.size() + 4, anchors[1]);
                            digest.field(anchors[0]);
                            digest.field(anchors[1]);
                        }
                    }
                    insert.executeUpdate();
                }
                if (digest != null) { digest.field(count); }
            }
        }
    }

    private Long renewalAnchor(Connection c, String rowKey) throws SQLException {
        try (var statement = c.prepareStatement("SELECT _probe_renewal_anchor FROM masks WHERE row_key=?")) {
            statement.setString(1, rowKey);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Non-renewing patch requires a committed row"); }
                long anchor = rows.getLong(1);
                if (rows.wasNull()) { throw new SQLException("Typed probe requires an inherited deadline anchor"); }
                return anchor;
            }
        }
    }

    private static String anchorIso(String alias) {
        return "CASE WHEN " + alias + ".effective_millis%1000=0 THEN "
                + "strftime('%Y-%m-%dT%H:%M:%SZ'," + alias + ".effective_millis/1000.0,'unixepoch') ELSE "
                + "strftime('%Y-%m-%dT%H:%M:%fZ'," + alias + ".effective_millis/1000.0,'unixepoch') END";
    }

    private Long[] sourceAnchors(Connection c, long rowId, String source, long occurrences,
                                 long newUnit) throws SQLException {
        try (var statement = c.prepareStatement("""
                SELECT occurrences,_probe_first_anchor,_probe_last_anchor
                FROM masks_sources WHERE row_id=? AND source_key=?
                """)) {
            statement.setLong(1, rowId);
            statement.setString(2, source);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { return new Long[]{newUnit, newUnit}; }
                long first = rows.getLong(2);
                Long firstValue = rows.wasNull() ? null : first;
                long last = rows.getLong(3);
                Long lastValue = rows.wasNull() ? null : last;
                return new Long[]{firstValue, rows.getLong(1) == occurrences ? lastValue : newUnit};
            }
        }
    }

    private Long firstAnchor(Connection c, long rowId, long newUnit) throws SQLException {
        try (var statement = c.prepareStatement("SELECT _probe_first_anchor FROM masks WHERE id=?")) {
            statement.setLong(1, rowId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { return newUnit; }
                long anchor = rows.getLong(1);
                return rows.wasNull() ? null : anchor;
            }
        }
    }

    private void copyRegistration(Connection source, Connection destination,
                                  RegisteredObservation registration) throws SQLException {
        try (var control = destination.prepareStatement(
                "UPDATE observation_order_control SET namespace_id=? WHERE singleton_id=1")) {
            control.setString(1, registration.namespaceId());
            control.executeUpdate();
        }
        try (var select = source.prepareStatement("SELECT * FROM registered_observation WHERE occurrence_id=?");
             var insert = destination.prepareStatement("""
                     INSERT OR IGNORE INTO registered_observation
                     (occurrence_id,admission_order,origin_kind,registered_at_ms,terminal_at_ms) VALUES (?,?,?,?,?)
                     """)) {
            select.setString(1, registration.observationId().value());
            try (var rows = select.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Missing pinned registration"); }
                insert.setString(1, rows.getString("occurrence_id"));
                insert.setLong(2, rows.getLong("admission_order"));
                insert.setString(3, rows.getString("origin_kind"));
                insert.setLong(4, rows.getLong("registered_at_ms"));
                insert.setObject(5, rows.getObject("terminal_at_ms"));
                insert.executeUpdate();
            }
        }
    }

    private String recordKey(String mask) {
        return keys.recordKeyOf("masks", row(mask, null, null)).orElseThrow().keyHash();
    }

    private ArtifactRow row(String mask, String name, String source) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", null);
        values.put("mask", mask);
        values.put("name", name);
        values.put("source", source);
        return ArtifactRow.ordered(values);
    }

    private static HikariDataSource database(Path path) {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("kernel-experiment", "jdbc:sqlite:" + path, "low-memory", 3, 3));
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement()) { statement.execute(sql); }
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
                throw new IocExtractorException("Typed publication experiment failed", failure);
            }
        });
    }

    private static void rollback(Connection c, Throwable failure) {
        try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
    }

    @Override public void close() {
        try { working.close(); } finally { published.close(); }
    }

    @FunctionalInterface private interface Work<T> { T apply(Connection c) throws SQLException; }

    /** Caller-controlled wall clock; effective UTC still goes through JdbcLifecycleClock. */
    static final class MutableClock extends Clock {
        private Instant value;
        MutableClock(Instant value) { this.value = value; }
        void advance(Duration duration) { value = value.plus(duration); }
        @Override public Instant instant() { return value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) { throw new IllegalArgumentException("UTC probe only"); }
            return this;
        }
    }
}
