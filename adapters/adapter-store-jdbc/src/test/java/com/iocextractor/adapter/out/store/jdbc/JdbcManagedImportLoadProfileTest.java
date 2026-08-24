package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportPromotionPolicy;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in P9 reference-load qualification for the complete JDBC import path. */
@EnabledIfSystemProperty(named = "ioc.import.load.enabled", matches = "true")
class JdbcManagedImportLoadProfileTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ARTIFACT = "masks";
    private static final String KEY_COLUMN = "mask";
    private static final String RECORD_KEY = "mask-row-v1";
    private static final String MATCH_KEY = "mask-match-v1";
    private static final String DIGEST = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);
    private static final int SQL_BATCH = 1_000;

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void completesConfiguredReferenceProfileWithinRecordedResourceEnvelope() throws Exception {
        LoadProfile profile = LoadProfile.fromSystemProperties();
        Environment environment = createEnvironment(profile);
        resetHeapPeaks();

        long stagingStarted = System.nanoTime();
        CanonicalImportCommand command = stage(environment, profile);
        Duration stagingDuration = elapsed(stagingStarted);
        long stageBytes = Files.size(environment.sealedStage(command));
        long heapAfterStaging = peakHeapBytes();
        List<String> queryPlans = queryPlans(environment, command);

        long promotionStarted = System.nanoTime();
        var result = environment.writer().promote(command);
        Duration promotionDuration = elapsed(promotionStarted);
        long heapAfterPromotion = peakHeapBytes();
        long dataframeBytes = databaseBytes(environment.database());

        assertThat(result.acceptedRows()).isEqualTo(profile.expectedAcceptedRows());
        assertThat(result.rejectedRows()).isEqualTo(profile.expectedRejectedRows());
        assertThat(result.publicMutations()).isEqualTo(profile.expectedPublicMutations());
        assertThat(environment.count(ARTIFACT)).isEqualTo(profile.expectedCanonicalRows());
        assertThat(environment.count("import_commit")).isOne();
        assertThat(queryPlans).allMatch(plan -> plan.contains("INDEX") || plan.contains("PRIMARY KEY"));
        assertThat(stagingDuration.plus(promotionDuration)).isLessThan(profile.maximumDuration());
        assertThat(Math.max(heapAfterStaging, heapAfterPromotion)).isLessThan(profile.maximumHeapBytes());

        metric("profile", profile.name());
        metric("rows", profile.rows());
        metric("seed_rows", profile.seedRows());
        metric("accepted_rows", result.acceptedRows());
        metric("rejected_rows", result.rejectedRows());
        metric("public_mutations", result.publicMutations());
        metric("staging_ms", stagingDuration.toMillis());
        metric("promotion_ms", promotionDuration.toMillis());
        metric("total_ms", stagingDuration.plus(promotionDuration).toMillis());
        metric("stage_bytes", stageBytes);
        metric("dataframe_bytes", dataframeBytes);
        metric("heap_max_bytes", Runtime.getRuntime().maxMemory());
        metric("peak_heap_bytes", Math.max(heapAfterStaging, heapAfterPromotion));
        queryPlans.forEach(plan -> metric("query_plan", plan));
    }

    private Environment createEnvironment(LoadProfile profile) throws SQLException {
        Path database = tempDir.resolve("dataframe.db");
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + database, "low-memory", 4, 4));
        List<DataframeArtifactSchema> schemas = List.of(schema());
        List<ArtifactIdentityDefinition> identities = List.of(identity());
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        new JdbcArtifactIdentityStore(dataSource, CLOCK).ensureAll(identities);
        activate(schemas);
        Environment environment = new Environment(
                dataSource, database, tempDir.resolve("stages"), schemas, identities,
                new CanonicalArtifactKeyResolver(identities));
        if (profile.seedRows() > 0) {
            seedCanonical(environment, profile.seedRows());
        }
        return environment;
    }

    private CanonicalImportCommand stage(Environment environment, LoadProfile profile) {
        ImportDeliveryId deliveryId = new ImportDeliveryId("load-" + profile.name());
        ImportSnapshot snapshot = new ImportSnapshot(
                new ImportSnapshotReference("snapshot:" + profile.name()),
                new ImportSha256(DIGEST), profile.rows() * 64L);
        ImportContractPin contract = new ImportContractPin(
                new ImportContractId("load-contract-v1"), 1,
                new ImportContractFingerprint(FINGERPRINT));
        CreateImportWorkspaceCommand create = new CreateImportWorkspaceCommand(
                deliveryId, snapshot, contract, ImportDuplicatePolicy.COALESCE,
                ImportPromotionPolicy.defaults());
        JdbcImportWorkspace workspace = new JdbcImportWorkspace(
                environment.workspaceRoot(), limits(profile.rows()), CLOCK);
        ImportStage stage;
        try (ImportWorkspaceWriter writer = workspace.create(create)) {
            for (int index = 0; index < profile.rows(); index++) {
                writer.append(row(environment, profile, index));
            }
            stage = writer.seal();
        }
        workspace.verifySealed(create, stage);
        return new CanonicalImportCommand(
                deliveryId, new ImportDeliverySequence(1), new ImportSourceId("load-source"),
                snapshot, contract, stage);
    }

    private ImportLogicalRow row(Environment environment, LoadProfile profile, int index) {
        RowKind kind = profile.kind(index);
        int canonicalIndex = kind == RowKind.INSERT ? profile.seedRows() + index : index;
        String mask = mask(canonicalIndex);
        String description = switch (kind) {
            case INSERT -> "inserted";
            case UPDATE -> "updated";
            case NO_OP -> "seed";
            case CONFLICT -> "conflict";
        };
        Map<String, ImportCell> cells = new LinkedHashMap<>();
        cells.put(KEY_COLUMN, ImportCell.value(mask));
        cells.put("source", ImportCell.value("load-source"));
        cells.put("description", ImportCell.value(description));
        Map<String, ImportMergePolicy> policies = new LinkedHashMap<>();
        policies.put(KEY_COLUMN, ImportMergePolicy.KEEP_EXISTING);
        policies.put("source", ImportMergePolicy.AUTHORITATIVE);
        policies.put("description", kind == RowKind.CONFLICT
                ? ImportMergePolicy.REJECT_CONFLICT
                : ImportMergePolicy.AUTHORITATIVE);
        ArtifactRow publicRow = publicRow(null, mask, description);
        CanonicalKeyMaterial record = environment.keyResolver()
                .recordKeyOf(ARTIFACT, publicRow).orElseThrow();
        ImportArtifactBranch branch = new ImportArtifactBranch(
                ARTIFACT, ImportArtifactRole.PRIMARY, cells, policies, OptionalLong.empty(),
                Optional.of(record), environment.keyResolver().matchKeysOf(ARTIFACT, publicRow));
        return new ImportLogicalRow(index + 2L, List.of(branch));
    }

    private void seedCanonical(Environment environment, int rows) throws SQLException {
        long validUntil = NOW.plus(Duration.ofHours(12)).toEpochMilli();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO masks(
                         id, mask, source, description, row_key, _created_at,
                         _first_source_key, _lifecycle_id, _first_confirmed_at_epoch_ms,
                         _last_confirmed_at_epoch_ms, _valid_until_epoch_ms)
                     VALUES (?, ?, 'load-source', 'seed', ?, ?, 'load-seed', ?, ?, ?, ?)
                     """);
             PreparedStatement alias = connection.prepareStatement("""
                     INSERT INTO canonical_match_alias(
                         artifact, definition_id, key_hash, key_canonical,
                         lifecycle_id, canonical_row_id)
                     VALUES ('masks', ?, ?, ?, ?, ?)
                     """)) {
            connection.setAutoCommit(false);
            for (int index = 0; index < rows; index++) {
                long id = index + 1L;
                String mask = mask(index);
                ArtifactRow row = ArtifactRow.ordered(Map.of(
                        "id", Long.toString(id), KEY_COLUMN, mask,
                        "source", "load-source", "description", "seed"));
                CanonicalKeyMaterial record = environment.keyResolver()
                        .recordKeyOf(ARTIFACT, row).orElseThrow();
                CanonicalKeyMaterial match = environment.keyResolver()
                        .matchKeysOf(ARTIFACT, row).getFirst();
                insert.setLong(1, id);
                insert.setString(2, mask);
                insert.setString(3, record.keyHash());
                insert.setString(4, NOW.toString());
                insert.setLong(5, id);
                insert.setLong(6, NOW.toEpochMilli());
                insert.setLong(7, NOW.toEpochMilli());
                insert.setLong(8, validUntil);
                insert.addBatch();
                alias.setString(1, match.definitionId());
                alias.setString(2, match.keyHash());
                alias.setString(3, match.keyCanonical());
                alias.setLong(4, id);
                alias.setLong(5, id);
                alias.addBatch();
                if ((index + 1) % SQL_BATCH == 0) {
                    insert.executeBatch();
                    alias.executeBatch();
                }
            }
            insert.executeBatch();
            alias.executeBatch();
            try (PreparedStatement lifecycleAllocator = connection.prepareStatement("""
                    UPDATE lifecycle_id_allocator
                    SET next_value = ?, updated_at_ms = ?
                    WHERE singleton_id = 1
                    """)) {
                lifecycleAllocator.setLong(1, rows + 1L);
                lifecycleAllocator.setLong(2, NOW.toEpochMilli());
                assertThat(lifecycleAllocator.executeUpdate()).isOne();
            }
            connection.commit();
        }
    }

    private List<String> queryPlans(Environment environment, CanonicalImportCommand command) throws SQLException {
        List<String> plans = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute(
                    "ATTACH DATABASE '" + environment.sealedStage(command).toAbsolutePath()
                            .toString().replace("'", "''") + "' AS import_stage");
            plans.add(plan(connection, """
                    EXPLAIN QUERY PLAN
                    SELECT branch.branch_id
                    FROM import_stage.stage_branch branch
                    JOIN import_stage.stage_input_row input
                      ON input.source_row_number = branch.source_row_number
                    JOIN import_stage.stage_match_key match_key
                      ON match_key.branch_id = branch.branch_id
                    WHERE input.status = 'ACCEPTED'
                      AND branch.artifact = 'masks'
                      AND match_key.definition_id = 'mask-match-v1'
                      AND match_key.key_hash = 'probe'
                    """));
            plans.add(plan(connection, """
                    EXPLAIN QUERY PLAN SELECT id FROM masks WHERE row_key = 'probe'
                    """));
            plans.add(plan(connection, """
                    EXPLAIN QUERY PLAN
                    SELECT canonical_row_id FROM canonical_match_alias
                    WHERE artifact = 'masks' AND definition_id = 'mask-match-v1'
                      AND key_hash = 'probe' AND key_canonical = '[\"probe\"]'
                    """));
            plans.add(plan(connection, """
                    EXPLAIN QUERY PLAN SELECT accepted_rows FROM import_commit
                    WHERE delivery_id = 'load-probe'
                    """));
            connection.createStatement().execute("DETACH DATABASE import_stage");
        }
        return List.copyOf(plans);
    }

    private String plan(Connection connection, String sql) throws SQLException {
        List<String> details = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                details.add(rows.getString("detail"));
            }
        }
        return String.join(" | ", details);
    }

    private void activate(List<DataframeArtifactSchema> schemas) {
        var control = new JdbcLifecycleControlStore(dataSource, schemas);
        var disabled = control.load();
        var activating = disabled.beginActivation("import-load-fixed-12h-v1");
        assertThat(control.compareAndSet(disabled, activating)).isTrue();
        assertThat(control.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();
    }

    private DataframeArtifactSchema schema() {
        return new DataframeArtifactSchema(ARTIFACT, List.of(
                new DataframeColumn("id", "INTEGER"),
                new DataframeColumn(KEY_COLUMN, "TEXT"),
                new DataframeColumn("source", "TEXT"),
                new DataframeColumn("description", "TEXT")));
    }

    private ArtifactIdentityDefinition identity() {
        return new ArtifactIdentityDefinition(
                ARTIFACT,
                new CanonicalKeyDefinition(RECORD_KEY, CanonicalKeyMode.COMPOSITE, List.of(KEY_COLUMN)),
                List.of(new CanonicalKeyDefinition(
                        MATCH_KEY, CanonicalKeyMode.COMPOSITE, List.of(KEY_COLUMN))),
                1);
    }

    private ImportWorkspaceLimits limits(long rows) {
        return new ImportWorkspaceLimits(
                rows, 4, 32, rows,
                2L * 1024 * 1024 * 1024,
                8L * 1024 * 1024 * 1024,
                7L * 1024 * 1024 * 1024,
                6L * 1024 * 1024 * 1024,
                SQL_BATCH,
                new DelimitedInputLimits(rows, 32, 64 * 1024, 8 * 1024 * 1024));
    }

    private String mask(int index) {
        return "ioc-" + index + ".example";
    }

    private ArtifactRow publicRow(String id, String mask, String description) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put(KEY_COLUMN, mask);
        values.put("source", "load-source");
        values.put("description", description);
        return ArtifactRow.ordered(values);
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private long databaseBytes(Path database) throws Exception {
        long bytes = Files.size(database);
        Path wal = database.resolveSibling(database.getFileName() + "-wal");
        Path sharedMemory = database.resolveSibling(database.getFileName() + "-shm");
        if (Files.exists(wal)) {
            bytes = Math.addExact(bytes, Files.size(wal));
        }
        if (Files.exists(sharedMemory)) {
            bytes = Math.addExact(bytes, Files.size(sharedMemory));
        }
        return bytes;
    }

    private void resetHeapPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private long peakHeapBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private void metric(String key, Object value) {
        System.out.printf("IMPORT_LOAD_METRIC %s=%s%n", key, value);
    }

    private record Environment(
            HikariDataSource dataSource,
            Path database,
            Path workspaceRoot,
            List<DataframeArtifactSchema> schemas,
            List<ArtifactIdentityDefinition> identities,
            CanonicalArtifactKeyResolver keyResolver) {

        private JdbcCanonicalImportWriter writer() {
            return new JdbcCanonicalImportWriter(
                    dataSource, schemas,
                    List.of(new ArtifactIdAllocatorDefinition(
                            ARTIFACT, ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot,
                    ignored -> EffectiveTime.at(NOW),
                    new FixedRecordValidityPolicy(Duration.ofHours(12)), CLOCK,
                    new JdbcWriterAdmission(), JdbcCanonicalImportObserver.NOOP, Duration.ofDays(90));
        }

        private Path sealedStage(CanonicalImportCommand command) {
            return new ImportWorkspaceLayout(workspaceRoot).paths(command.deliveryId()).sealed();
        }

        private long count(String table) throws SQLException {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM \"" + table + "\"");
                 ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : -1;
            }
        }
    }

    private enum RowKind {
        INSERT,
        UPDATE,
        NO_OP,
        CONFLICT
    }

    private record LoadProfile(
            String name,
            int rows,
            int seedRows,
            Duration maximumDuration,
            long maximumHeapBytes) {

        private static LoadProfile fromSystemProperties() {
            String profile = System.getProperty("ioc.import.load.profile", "insert");
            int rows = Integer.getInteger("ioc.import.load.rows", 100_000);
            if (rows <= 0 || rows > 1_000_000) {
                throw new IllegalArgumentException("ioc.import.load.rows must be between 1 and 1000000");
            }
            if (!"insert".equals(profile) && !"mixed".equals(profile)) {
                throw new IllegalArgumentException("ioc.import.load.profile must be insert or mixed");
            }
            if ("mixed".equals(profile) && rows % 4 != 0) {
                throw new IllegalArgumentException("mixed profile row count must be divisible by four");
            }
            long maximumSeconds = Long.getLong("ioc.import.load.max-seconds", 3_600L);
            long maximumHeapMib = Long.getLong("ioc.import.load.max-heap-mib", 768L);
            return new LoadProfile(
                    profile, rows, "mixed".equals(profile) ? rows * 3 / 4 : 0,
                    Duration.ofSeconds(maximumSeconds), maximumHeapMib * 1024 * 1024);
        }

        private RowKind kind(int index) {
            if ("insert".equals(name)) {
                return RowKind.INSERT;
            }
            int quarter = rows / 4;
            if (index < quarter) {
                return RowKind.UPDATE;
            }
            if (index < quarter * 2) {
                return RowKind.NO_OP;
            }
            if (index < quarter * 3) {
                return RowKind.CONFLICT;
            }
            return RowKind.INSERT;
        }

        private long expectedAcceptedRows() {
            return "mixed".equals(name) ? rows * 3L / 4 : rows;
        }

        private long expectedRejectedRows() {
            return "mixed".equals(name) ? rows / 4L : 0;
        }

        private long expectedPublicMutations() {
            return "mixed".equals(name) ? rows / 2L : rows;
        }

        private long expectedCanonicalRows() {
            return Math.addExact(seedRows, "mixed".equals(name) ? rows / 4 : rows);
        }
    }
}
