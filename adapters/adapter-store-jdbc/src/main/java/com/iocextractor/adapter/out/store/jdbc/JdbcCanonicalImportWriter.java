package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdReservation;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalRecordMutationKind;
import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.RecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.ValidityDecision;
import com.iocextractor.application.dataframeimport.mapping.ImportMergeResolver;
import com.iocextractor.application.dataframeimport.mapping.ImportMergeResult;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.epochMillis;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/**
 * Delivery-scoped SQLite promotion writer.
 *
 * <p>The sealed stage is verified before fair writer admission, attached
 * immutable/read-only, planned through temporary tables, and then applied with
 * lifecycle, aliases, slots, revisions, rejection evidence and one idempotency
 * receipt in a single dataframe transaction.</p>
 */
public final class JdbcCanonicalImportWriter implements CanonicalImportWriter {

    private static final Duration DEFAULT_RECEIPT_RETENTION = Duration.ofDays(90);
    private static final String STAGE_SCHEMA = "import_stage";
    private static final String IMPORT_SOURCE_PREFIX = "dataframe-import:";
    private static final String STAGE_HEADER_COLUMNS = """
            SELECT schema_version, snapshot_sha256, snapshot_size, contract_id,
                   contract_version, contract_fingerprint, row_failure_policy, renew_unchanged,
                   slot_profile, existing_slot_policy, source_row_count, accepted_count,
                   rejected_count, sealed_at_ms
            FROM
            """;

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final Map<String, JdbcArtifactIdAllocator> publicIdAllocators;
    private final JdbcLifecycleIdAllocator lifecycleIdAllocator;
    private final JdbcCanonicalMutationEngine mutationEngine;
    private final CanonicalArtifactKeyResolver keyResolver;
    private final JdbcExportSlotRegistry exportSlots = new JdbcExportSlotRegistry();
    private final ImportMergeResolver mergeResolver = new ImportMergeResolver();
    private final ImportWorkspaceLayout workspaceLayout;
    private final ConnectionTimeSource timeSource;
    private final RecordValidityPolicy validityPolicy;
    private final JdbcWriterAdmission writerAdmission;
    private final JdbcCanonicalImportObserver observer;
    private final Duration receiptRetention;

    /** Creates a production writer participating in shared fair admission. */
    public JdbcCanonicalImportWriter(
            DataSource dataSource,
            List<DataframeArtifactSchema> schemas,
            List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
            List<ArtifactIdentityDefinition> identityDefinitions,
            Path workspaceRoot,
            JdbcLifecycleClock timeSource,
            RecordValidityPolicy validityPolicy,
            Clock allocatorClock,
            JdbcWriterAdmission writerAdmission) {
        this(dataSource, schemas, publicIdDefinitions, identityDefinitions, workspaceRoot,
                Objects.requireNonNull(timeSource, "timeSource")::now,
                validityPolicy, allocatorClock, writerAdmission,
                JdbcCanonicalImportObserver.NOOP, DEFAULT_RECEIPT_RETENTION);
    }

    JdbcCanonicalImportWriter(
            DataSource dataSource,
            List<DataframeArtifactSchema> schemas,
            List<ArtifactIdAllocatorDefinition> publicIdDefinitions,
            List<ArtifactIdentityDefinition> identityDefinitions,
            Path workspaceRoot,
            ConnectionTimeSource timeSource,
            RecordValidityPolicy validityPolicy,
            Clock allocatorClock,
            JdbcWriterAdmission writerAdmission,
            JdbcCanonicalImportObserver observer,
            Duration receiptRetention) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemas = schemasByName(schemas);
        List<ArtifactIdentityDefinition> identities = List.copyOf(
                Objects.requireNonNull(identityDefinitions, "identityDefinitions"));
        this.keyResolver = new CanonicalArtifactKeyResolver(identities);
        this.mutationEngine = new JdbcCanonicalMutationEngine(dataSource, schemas, identities);
        this.workspaceLayout = new ImportWorkspaceLayout(workspaceRoot);
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.validityPolicy = Objects.requireNonNull(validityPolicy, "validityPolicy");
        this.writerAdmission = Objects.requireNonNull(writerAdmission, "writerAdmission");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.receiptRetention = requirePositive(receiptRetention, "receiptRetention");
        Objects.requireNonNull(allocatorClock, "allocatorClock");
        this.lifecycleIdAllocator = new JdbcLifecycleIdAllocator(dataSource, allocatorClock);
        this.publicIdAllocators = initializePublicIdAllocators(publicIdDefinitions, allocatorClock);
    }

    @Override
    public CanonicalImportResult promote(CanonicalImportCommand command) {
        Objects.requireNonNull(command, "command");
        Optional<CanonicalImportResult> committed = loadCommitted(command);
        if (committed.isPresent()) {
            return replayed(committed.orElseThrow());
        }
        VerifiedStage stage = verifyStage(command);
        ReservedIds reservations = reserveWorstCase(stage);
        return writerAdmission.execute(() -> promoteAdmitted(command, stage, reservations));
    }

    private CanonicalImportResult promoteAdmitted(CanonicalImportCommand command,
                                                   VerifiedStage stage,
                                                   ReservedIds reservations) {
        try (Connection connection = dataSource.getConnection()) {
            return promote(connection, command, stage, reservations);
        } catch (IocExtractorException failure) {
            throw failure;
        } catch (SQLException | RuntimeException failure) {
            throw new IocExtractorException(
                    "Failed atomic canonical dataframe import promotion", failure);
        }
    }

    private CanonicalImportResult promote(Connection connection,
                                          CanonicalImportCommand command,
                                          VerifiedStage stage,
                                          ReservedIds reservations) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        boolean stageAttached = false;
        try {
            JdbcLifecycleTransactions.acquireActiveWriteOwnership(connection);
            Optional<CanonicalImportResult> raced = loadCommitted(connection, command);
            if (raced.isPresent()) {
                connection.commit();
                return replayed(raced.orElseThrow());
            }
            EffectiveTime asOf = Objects.requireNonNull(timeSource.now(connection), "import effective time");
            ValidityDecision validity = validityPolicy.decide(asOf).requireValidAt(asOf);
            attachSealedStage(connection, stage.path());
            stageAttached = true;
            verifyAttachedMeta(connection, command, stage.header());
            observer.after(JdbcCanonicalImportObserver.Phase.STAGE_ATTACHED);

            createPlanningTables(connection);
            planActiveMatches(connection, asOf);
            observer.after(JdbcCanonicalImportObserver.Phase.ACTIVE_MATCHES_PLANNED);
            planMerges(connection, stage.header(), asOf);
            rejectCrossRowConflicts(connection);
            observer.after(JdbcCanonicalImportObserver.Phase.MERGES_PLANNED);

            PromotionCounts counts = applyFailurePolicy(connection, stage.header());
            observer.after(JdbcCanonicalImportObserver.Phase.FAILURE_POLICY_PASSED);
            MutationSummary mutations = applyMutations(
                    connection, command, reservations, asOf, validity);
            observer.after(JdbcCanonicalImportObserver.Phase.CANONICAL_MUTATIONS_APPLIED);

            List<SlotResolution> slotResolutions = reconcilePreferredSlots(
                    connection, stage.header(), mutations.slotRequests(), asOf);
            observer.after(JdbcCanonicalImportObserver.Phase.SLOTS_RECONCILED);
            Map<String, Long> generations = advancePublicState(
                    connection, mutations.affectedArtifacts(), asOf);
            observer.after(JdbcCanonicalImportObserver.Phase.REVISIONS_ADVANCED);

            CanonicalImportResult result = new CanonicalImportResult(
                    ImportPromotionOutcome.COMMITTED,
                    counts.acceptedRows(), counts.rejectedRows(), mutations.publicMutations(),
                    mutations.affectedArtifacts(), mutations.observedArtifacts(), generations, asOf.value());
            insertReceipt(connection, command, result);
            persistRejections(connection, command);
            persistSlotResolutions(connection, command, slotResolutions);
            persistArtifactEvidence(connection, command, result);
            observer.after(JdbcCanonicalImportObserver.Phase.RECEIPT_WRITTEN);
            observer.after(JdbcCanonicalImportObserver.Phase.BEFORE_COMMIT);
            connection.commit();
            observer.after(JdbcCanonicalImportObserver.Phase.AFTER_COMMIT);
            return result;
        } catch (SQLException | RuntimeException caught) {
            failure = caught;
            JdbcLifecycleTransactions.rollback(connection, caught);
            throw caught;
        } finally {
            try {
                if (stageAttached) {
                    detachSealedStage(connection);
                }
            } catch (SQLException detachFailure) {
                if (failure != null) {
                    failure.addSuppressed(detachFailure);
                } else {
                    failure = detachFailure;
                    throw detachFailure;
                }
            } finally {
                JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
            }
        }
    }

    private VerifiedStage verifyStage(CanonicalImportCommand command) {
        ImportWorkspaceLayout.WorkspacePaths paths = workspaceLayout.paths(command.deliveryId());
        workspaceLayout.requireReference(command.deliveryId(), command.stage().reference());
        if (!Files.isRegularFile(paths.sealed(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IocExtractorException("Pinned import stage is not a regular sealed file");
        }
        ImportSha256 digest = digest(paths.sealed());
        if (!digest.equals(command.stage().digest())) {
            throw new IocExtractorException("Pinned import stage digest does not match sealed bytes");
        }
        String url = "jdbc:sqlite:" + immutableUri(paths.sealed());
        try (Connection stageConnection = java.sql.DriverManager.getConnection(url)) {
            try (Statement statement = stageConnection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!integrity.next() || !"ok".equals(integrity.getString(1))) {
                        throw new IocExtractorException("Pinned import stage integrity check failed");
                    }
                }
            }
            StageHeader header = readLocalStageHeader(stageConnection, command.deliveryId().value());
            requirePinnedHeader(command, header);
            Map<String, Integer> branchCounts = new LinkedHashMap<>();
            try (PreparedStatement statement = stageConnection.prepareStatement("""
                    SELECT branch.artifact, COUNT(*)
                    FROM stage_branch branch
                    JOIN stage_input_row input
                      ON input.source_row_number = branch.source_row_number
                    WHERE input.status = 'ACCEPTED' AND branch.status = 'ACCEPTED'
                    GROUP BY branch.artifact
                    ORDER BY branch.artifact
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    branchCounts.put(rows.getString(1), rows.getInt(2));
                }
            }
            return new VerifiedStage(paths.sealed(), header, Map.copyOf(branchCounts));
        } catch (SQLException failure) {
            throw new IocExtractorException("Cannot verify pinned sealed import stage", failure);
        }
    }

    private ReservedIds reserveWorstCase(VerifiedStage stage) {
        int branches = stage.branchCounts().values().stream().mapToInt(Integer::intValue).sum();
        Map<String, ArtifactIdReservation> publicIds = new LinkedHashMap<>();
        for (var entry : stage.branchCounts().entrySet()) {
            DataframeArtifactSchema schema = requireSchema(entry.getKey());
            if (hasPublicId(schema)) {
                JdbcArtifactIdAllocator allocator = publicIdAllocators.get(entry.getKey());
                if (allocator == null) {
                    throw new IocExtractorException(
                            "Public id allocator is not configured for import artifact: " + entry.getKey());
                }
                publicIds.put(entry.getKey(), allocator.reserve(entry.getKey(), entry.getValue()));
            }
        }
        return new ReservedIds(Map.copyOf(publicIds), lifecycleIdAllocator.reserve(branches));
    }

    private void attachSealedStage(Connection connection, Path path) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "ATTACH DATABASE ? AS " + STAGE_SCHEMA)) {
            statement.setString(1, immutableUri(path));
            statement.execute();
        }
    }

    private void detachSealedStage(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DETACH DATABASE " + STAGE_SCHEMA);
        }
    }

    private void verifyAttachedMeta(Connection connection,
                                    CanonicalImportCommand command,
                                    StageHeader expected) throws SQLException {
        StageHeader actual = readAttachedStageHeader(connection, command.deliveryId().value());
        requirePinnedHeader(command, actual);
        if (!expected.equals(actual)) {
            throw new IocExtractorException("Attached import stage metadata changed after verification");
        }
    }

    private StageHeader readLocalStageHeader(Connection connection, String deliveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                STAGE_HEADER_COLUMNS + " stage_meta WHERE delivery_id = ?")) {
            return readStageHeader(statement, deliveryId);
        }
    }

    private StageHeader readAttachedStageHeader(Connection connection, String deliveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                STAGE_HEADER_COLUMNS + " import_stage.stage_meta WHERE delivery_id = ?")) {
            return readStageHeader(statement, deliveryId);
        }
    }

    private StageHeader readStageHeader(PreparedStatement statement, String deliveryId) throws SQLException {
        statement.setString(1, deliveryId);
        try (ResultSet row = statement.executeQuery()) {
            if (!row.next() || row.getInt("schema_version") != ImportWorkspaceSchema.VERSION
                    || row.getObject("sealed_at_ms") == null) {
                throw new IocExtractorException("Import stage metadata is missing or unsealed");
            }
            StageHeader header = new StageHeader(
                    row.getString("snapshot_sha256"), row.getLong("snapshot_size"),
                    row.getString("contract_id"), row.getInt("contract_version"),
                    row.getString("contract_fingerprint"),
                    ImportRowFailurePolicy.valueOf(row.getString("row_failure_policy")),
                    row.getInt("renew_unchanged") == 1,
                    Optional.ofNullable(row.getString("slot_profile")),
                    Optional.ofNullable(row.getString("existing_slot_policy"))
                            .map(ImportExistingSlotPolicy::valueOf),
                    row.getLong("source_row_count"), row.getLong("accepted_count"),
                    row.getLong("rejected_count"));
            if (row.next()) {
                throw new IocExtractorException("Import stage contains duplicate metadata");
            }
            return header;
        }
    }

    private void requirePinnedHeader(CanonicalImportCommand command, StageHeader header) {
        boolean matches = command.snapshot().digest().value().equals(header.snapshotDigest())
                && command.snapshot().size() == header.snapshotSize()
                && command.contract().id().value().equals(header.contractId())
                && command.contract().version() == header.contractVersion()
                && command.contract().fingerprint().value().equals(header.contractFingerprint())
                && command.stage().sourceRows() == header.sourceRows()
                && command.stage().acceptedRows() == header.acceptedRows()
                && command.stage().rejectedRows() == header.rejectedRows()
                && header.slotProfile().isPresent() == header.existingSlotPolicy().isPresent();
        if (!matches) {
            throw new IocExtractorException("Import stage metadata does not match promotion evidence");
        }
    }

    private void createPlanningTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS temp_import_final_cell");
            statement.execute("DROP TABLE IF EXISTS temp_import_rejection");
            statement.execute("DROP TABLE IF EXISTS temp_import_plan");
            statement.execute("DROP TABLE IF EXISTS temp_import_match");
            statement.execute("""
                    CREATE TEMP TABLE temp_import_match (
                        branch_id INTEGER NOT NULL,
                        canonical_row_id INTEGER NOT NULL,
                        lifecycle_id INTEGER NOT NULL,
                        PRIMARY KEY(branch_id, canonical_row_id)) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TEMP TABLE temp_import_plan (
                        branch_id INTEGER PRIMARY KEY,
                        source_row_number INTEGER NOT NULL,
                        artifact TEXT NOT NULL,
                        record_key_hash TEXT NOT NULL,
                        canonical_row_id INTEGER,
                        lifecycle_id INTEGER,
                        renew_ttl INTEGER NOT NULL,
                        requested_slot INTEGER)
                    """);
            statement.execute("""
                    CREATE TEMP TABLE temp_import_final_cell (
                        branch_id INTEGER NOT NULL,
                        target_column TEXT NOT NULL,
                        value TEXT,
                        PRIMARY KEY(branch_id, target_column)) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TEMP TABLE temp_import_rejection (
                        rejection_ordinal INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_row_number INTEGER NOT NULL,
                        artifact TEXT,
                        diagnostic_code TEXT NOT NULL)
                    """);
            statement.execute("""
                    INSERT INTO temp_import_rejection(source_row_number, artifact, diagnostic_code)
                    SELECT source_row_number, artifact, diagnostic_code
                    FROM import_stage.stage_row_error
                    """);
        }
    }

    private void planActiveMatches(Connection connection, EffectiveTime asOf) throws SQLException {
        for (DataframeArtifactSchema schema : schemas.values()) {
            String artifact = schema.artifactName();
            String aliases = """
                    INSERT OR IGNORE INTO temp_import_match(branch_id, canonical_row_id, lifecycle_id)
                    SELECT branch.branch_id, alias.canonical_row_id, alias.lifecycle_id
                    FROM import_stage.stage_branch branch
                    JOIN import_stage.stage_input_row input
                      ON input.source_row_number = branch.source_row_number
                    JOIN import_stage.stage_match_key match
                      ON match.branch_id = branch.branch_id
                    JOIN canonical_match_alias alias
                      ON alias.artifact = branch.artifact
                     AND alias.definition_id = match.definition_id
                     AND alias.key_hash = match.key_hash
                     AND alias.key_canonical = match.key_canonical
                    JOIN ${artifact} active
                      ON active.${id} = alias.canonical_row_id
                     AND active.${lifecycle} = alias.lifecycle_id
                    WHERE input.status = 'ACCEPTED'
                      AND branch.status = 'ACCEPTED'
                      AND branch.artifact = ?
                      AND active.${validUntil} > ?
                    """
                    .replace("${artifact}", quote(artifact))
                    .replace("${id}", quote("id"))
                    .replace("${lifecycle}", quote("_lifecycle_id"))
                    .replace("${validUntil}", quote("_valid_until_epoch_ms"));
            executeArtifactTime(connection, aliases, artifact, asOf);
            String recordKey = """
                    INSERT OR IGNORE INTO temp_import_match(branch_id, canonical_row_id, lifecycle_id)
                    SELECT branch.branch_id, active.${id}, active.${lifecycle}
                    FROM import_stage.stage_branch branch
                    JOIN import_stage.stage_input_row input
                      ON input.source_row_number = branch.source_row_number
                    JOIN ${artifact} active ON active.${rowKey} = branch.record_key_hash
                    WHERE input.status = 'ACCEPTED'
                      AND branch.status = 'ACCEPTED'
                      AND branch.artifact = ?
                      AND active.${validUntil} > ?
                    """
                    .replace("${artifact}", quote(artifact))
                    .replace("${id}", quote("id"))
                    .replace("${lifecycle}", quote("_lifecycle_id"))
                    .replace("${rowKey}", quote("row_key"))
                    .replace("${validUntil}", quote("_valid_until_epoch_ms"));
            executeArtifactTime(connection, recordKey, artifact, asOf);
        }
    }

    private void executeArtifactTime(Connection connection,
                                     String sql,
                                     String artifact,
                                     EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifact);
            statement.setLong(2, epochMillis(asOf));
            statement.executeUpdate();
        }
    }

    private void planMerges(Connection connection, StageHeader header, EffectiveTime asOf)
            throws SQLException {
        String sql = """
                SELECT branch.branch_id, branch.source_row_number, branch.artifact,
                       branch.record_key_hash, branch.requested_slot,
                       COUNT(match.canonical_row_id) AS match_count,
                       MIN(match.canonical_row_id) AS canonical_row_id,
                       MIN(match.lifecycle_id) AS lifecycle_id
                FROM import_stage.stage_branch branch
                JOIN import_stage.stage_input_row input
                  ON input.source_row_number = branch.source_row_number
                LEFT JOIN temp_import_match match ON match.branch_id = branch.branch_id
                WHERE input.status = 'ACCEPTED' AND branch.status = 'ACCEPTED'
                GROUP BY branch.branch_id, branch.source_row_number, branch.artifact,
                         branch.record_key_hash, branch.requested_slot
                ORDER BY branch.source_row_number, branch.branch_ordinal
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            long sourceRow = -1;
            List<BranchCandidate> branches = new ArrayList<>();
            while (rows.next()) {
                long nextSource = rows.getLong("source_row_number");
                if (sourceRow != -1 && sourceRow != nextSource) {
                    planLogicalRow(connection, sourceRow, branches, header, asOf);
                    branches.clear();
                }
                sourceRow = nextSource;
                branches.add(new BranchCandidate(
                        rows.getLong("branch_id"), nextSource, rows.getString("artifact"),
                        rows.getString("record_key_hash"), optionalLong(rows, "requested_slot"),
                        rows.getInt("match_count"), optionalLong(rows, "canonical_row_id"),
                        optionalLong(rows, "lifecycle_id")));
            }
            if (sourceRow != -1) {
                planLogicalRow(connection, sourceRow, branches, header, asOf);
            }
        }
    }

    private void planLogicalRow(Connection connection,
                                long sourceRow,
                                List<BranchCandidate> candidates,
                                StageHeader header,
                                EffectiveTime asOf) throws SQLException {
        List<PlannedBranch> planned = new ArrayList<>(candidates.size());
        Rejection rejection = null;
        for (BranchCandidate candidate : candidates) {
            if (candidate.matchCount() > 1) {
                rejection = new Rejection(candidate.artifact(), "IMPORT.MULTIPLE_ACTIVE_MATCH");
                break;
            }
            PlannedBranch branch = planBranch(connection, candidate, header, asOf);
            if (branch.rejection().isPresent()) {
                rejection = branch.rejection().orElseThrow();
                break;
            }
            planned.add(branch);
        }
        if (rejection != null) {
            insertRejection(connection, sourceRow, rejection.artifact(), rejection.code());
            return;
        }
        for (PlannedBranch branch : planned) {
            insertPlan(connection, branch);
        }
    }

    private PlannedBranch planBranch(Connection connection,
                                     BranchCandidate candidate,
                                     StageHeader header,
                                     EffectiveTime asOf) throws SQLException {
        DataframeArtifactSchema schema = requireSchema(candidate.artifact());
        JdbcCanonicalMutationEngine.StoredLifecycle stored = candidate.canonicalRowId().isPresent()
                ? mutationEngine.loadForImport(
                        connection, schema, candidate.canonicalRowId().orElseThrow())
                : null;
        if (stored != null && stored.validUntilEpochMs() <= epochMillis(asOf)) {
            return PlannedBranch.rejected(candidate, "IMPORT.MATCH_EXPIRED_DURING_PLAN");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (DataframeColumn column : schema.columns()) {
            values.put(column.name(), stored == null ? null : stored.publicRow().value(column.name()));
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target_column, merge_policy, presence, value
                FROM import_stage.stage_cell
                WHERE branch_id = ?
                ORDER BY target_column
                """)) {
            statement.setLong(1, candidate.branchId());
            try (ResultSet cells = statement.executeQuery()) {
                while (cells.next()) {
                    String column = cells.getString("target_column");
                    if (!values.containsKey(column) || "id".equals(column)) {
                        return PlannedBranch.rejected(candidate, "IMPORT.STAGE_COLUMN_INVALID");
                    }
                    ImportCell cell = importCell(cells);
                    ImportMergeResult merged = mergeResolver.resolve(
                            stored != null, values.get(column), cell,
                            ImportMergePolicy.valueOf(cells.getString("merge_policy")));
                    if (merged.decision() == ImportMergeResult.Decision.CONFLICT) {
                        return PlannedBranch.rejected(candidate, "IMPORT.MERGE_CONFLICT");
                    }
                    if (merged.decision() == ImportMergeResult.Decision.SET
                            || merged.decision() == ImportMergeResult.Decision.CLEAR) {
                        values.put(column, merged.value());
                    }
                }
            }
        }
        ArtifactRow finalRow = ArtifactRow.ordered(values);
        Optional<ArtifactRowKey> resolved = keyResolver.recordKeyOf(candidate.artifact(), finalRow)
                .map(key -> new ArtifactRowKey(key.keyHash()));
        if (resolved.isEmpty()) {
            return PlannedBranch.rejected(candidate, "IMPORT.RECORD_KEY_MISSING");
        }
        ArtifactRowKey key = resolved.orElseThrow();
        if (stored != null && !stored.rowKey().equals(key)) {
            return PlannedBranch.rejected(candidate, "IMPORT.STABLE_IDENTITY_CONFLICT");
        }
        if (stored == null && !candidate.recordKeyHash().equals(key.value())) {
            return PlannedBranch.rejected(candidate, "IMPORT.STAGED_IDENTITY_MISMATCH");
        }
        if (slotMismatch(connection, candidate, header, stored)) {
            return PlannedBranch.rejected(candidate, "IMPORT.EXISTING_SLOT_MISMATCH");
        }
        boolean publicChange = stored == null || publicChange(schema, stored.publicRow(), finalRow);
        boolean renew = stored == null || publicChange || header.renewUnchanged();
        return PlannedBranch.accepted(candidate, finalRow, renew);
    }

    private boolean slotMismatch(Connection connection,
                                 BranchCandidate candidate,
                                 StageHeader header,
                                 JdbcCanonicalMutationEngine.StoredLifecycle stored) throws SQLException {
        if (stored == null || candidate.requestedSlot().isEmpty()
                || header.slotProfile().isEmpty()
                || header.existingSlotPolicy().orElseThrow() != ImportExistingSlotPolicy.REJECT_MISMATCH) {
            return false;
        }
        String sql = "SELECT slot FROM export_slot_assignment "
                + "WHERE profile = ? AND artifact = ? AND lifecycle_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, header.slotProfile().orElseThrow());
            statement.setString(2, candidate.artifact());
            statement.setLong(3, stored.lifecycleId());
            try (ResultSet row = statement.executeQuery()) {
                long existing = row.next()
                        ? row.getLong(1)
                        : Long.parseLong(stored.publicRow().value("id"));
                return existing != candidate.requestedSlot().orElseThrow();
            }
        }
    }

    private boolean publicChange(DataframeArtifactSchema schema, ArtifactRow before, ArtifactRow after) {
        return schema.columns().stream()
                .filter(column -> !"id".equals(column.name()))
                .anyMatch(column -> !Objects.equals(
                        before.value(column.name()), after.value(column.name())));
    }

    private ImportCell importCell(ResultSet row) throws SQLException {
        int presence = row.getInt("presence");
        return switch (presence) {
            case 0 -> ImportCell.absent();
            case 1 -> ImportCell.nullValue();
            case 2 -> ImportCell.value(row.getString("value"));
            default -> throw new IocExtractorException("Import stage contains invalid cell presence");
        };
    }

    private void insertPlan(Connection connection, PlannedBranch branch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO temp_import_plan(
                    branch_id, source_row_number, artifact, record_key_hash,
                    canonical_row_id, lifecycle_id, renew_ttl, requested_slot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, branch.candidate().branchId());
            statement.setLong(2, branch.candidate().sourceRow());
            statement.setString(3, branch.candidate().artifact());
            statement.setString(4, branch.candidate().recordKeyHash());
            setOptionalLong(statement, 5, branch.candidate().canonicalRowId());
            setOptionalLong(statement, 6, branch.candidate().lifecycleId());
            statement.setInt(7, branch.renewTtl() ? 1 : 0);
            setOptionalLong(statement, 8, branch.candidate().requestedSlot());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO temp_import_final_cell(branch_id, target_column, value)
                VALUES (?, ?, ?)
                """)) {
            for (var entry : branch.finalRow().orElseThrow().values().entrySet()) {
                statement.setLong(1, branch.candidate().branchId());
                statement.setString(2, entry.getKey());
                statement.setString(3, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void rejectCrossRowConflicts(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO temp_import_rejection(
                        source_row_number, artifact, diagnostic_code)
                    SELECT source_row_number, artifact, 'IMPORT.DUPLICATE_ACTIVE_TARGET'
                    FROM temp_import_plan
                    WHERE lifecycle_id IS NOT NULL
                      AND (artifact, lifecycle_id) IN (
                          SELECT artifact, lifecycle_id
                          FROM temp_import_plan
                          WHERE lifecycle_id IS NOT NULL
                          GROUP BY artifact, lifecycle_id
                          HAVING COUNT(DISTINCT source_row_number) > 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO temp_import_rejection(
                        source_row_number, artifact, diagnostic_code)
                    SELECT source_row_number, artifact, 'IMPORT.DUPLICATE_SLOT_REQUEST'
                    FROM temp_import_plan
                    WHERE requested_slot IS NOT NULL
                      AND (artifact, requested_slot) IN (
                          SELECT artifact, requested_slot
                          FROM temp_import_plan
                          WHERE requested_slot IS NOT NULL
                          GROUP BY artifact, requested_slot
                          HAVING COUNT(DISTINCT source_row_number) > 1)
                    """);
            statement.executeUpdate("""
                    DELETE FROM temp_import_final_cell
                    WHERE branch_id IN (
                        SELECT plan.branch_id FROM temp_import_plan plan
                        JOIN temp_import_rejection rejection
                          ON rejection.source_row_number = plan.source_row_number)
                    """);
            statement.executeUpdate("""
                    DELETE FROM temp_import_plan
                    WHERE source_row_number IN (
                        SELECT source_row_number FROM temp_import_rejection)
                    """);
        }
    }

    private PromotionCounts applyFailurePolicy(Connection connection, StageHeader header) throws SQLException {
        long accepted = queryLong(connection,
                "SELECT COUNT(DISTINCT source_row_number) FROM temp_import_plan");
        long promotionRejected = Math.subtractExact(header.acceptedRows(), accepted);
        long rejected = Math.addExact(header.rejectedRows(), promotionRejected);
        if (header.rowFailurePolicy() == ImportRowFailurePolicy.REJECT_DELIVERY && rejected > 0) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO temp_import_rejection(
                            source_row_number, artifact, diagnostic_code)
                        SELECT DISTINCT source_row_number, artifact, 'IMPORT.DELIVERY_REJECTED'
                        FROM temp_import_plan
                        """);
                statement.executeUpdate("DELETE FROM temp_import_final_cell");
                statement.executeUpdate("DELETE FROM temp_import_plan");
            }
            return new PromotionCounts(0, Math.addExact(header.acceptedRows(), header.rejectedRows()));
        }
        return new PromotionCounts(accepted, rejected);
    }

    private MutationSummary applyMutations(Connection connection,
                                           CanonicalImportCommand command,
                                           ReservedIds reservations,
                                           EffectiveTime asOf,
                                           ValidityDecision validity) throws SQLException {
        Map<String, Integer> publicOffsets = new LinkedHashMap<>();
        int lifecycleOffset = 0;
        long publicMutations = 0;
        Set<String> affected = new LinkedHashSet<>();
        Set<String> observed = new LinkedHashSet<>();
        List<SlotRequest> slotRequests = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT branch_id, source_row_number, artifact, record_key_hash, canonical_row_id,
                       renew_ttl, requested_slot
                FROM temp_import_plan
                ORDER BY source_row_number, branch_id
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long branchId = rows.getLong("branch_id");
                String artifact = rows.getString("artifact");
                DataframeArtifactSchema schema = requireSchema(artifact);
                ArtifactRow finalRow = loadFinalRow(connection, branchId, schema);
                Optional<Long> canonicalRowId = optionalLong(rows, "canonical_row_id");
                CanonicalRecordMutationOutcome outcome;
                if (canonicalRowId.isPresent()) {
                    outcome = mutationEngine.mutateExisting(
                            connection, schema, canonicalRowId.orElseThrow(), finalRow,
                            rows.getInt("renew_ttl") == 1,
                            IMPORT_SOURCE_PREFIX + command.sourceId().value(), asOf, validity);
                } else {
                    ArtifactRow insertRow = materializePublicId(
                            artifact, schema, finalRow, reservations, publicOffsets);
                    outcome = mutationEngine.insertPlanned(
                            connection, schema, IMPORT_SOURCE_PREFIX + command.sourceId().value(),
                            insertRow, new ArtifactRowKey(rows.getString("record_key_hash")),
                            reservations.lifecycleIds().idAt(lifecycleOffset++), asOf, validity);
                }
                if (outcome.publicMutation()) {
                    publicMutations++;
                    affected.add(artifact);
                }
                if (outcome.kind() != CanonicalRecordMutationKind.NO_OP) {
                    observed.add(artifact);
                }
                Optional<Long> requested = optionalLong(rows, "requested_slot");
                if (requested.isPresent()) {
                    slotRequests.add(new SlotRequest(
                            rows.getLong("source_row_number"), artifact,
                            outcome.lifecycleId(), requested.orElseThrow(),
                            outcome.kind() == CanonicalRecordMutationKind.INSERTED
                                    || outcome.kind() == CanonicalRecordMutationKind.RESTARTED));
                }
            }
        }
        return new MutationSummary(publicMutations, Set.copyOf(affected),
                Set.copyOf(observed), List.copyOf(slotRequests));
    }

    private ArtifactRow materializePublicId(String artifact,
                                            DataframeArtifactSchema schema,
                                            ArtifactRow row,
                                            ReservedIds reservations,
                                            Map<String, Integer> offsets) {
        if (!hasPublicId(schema)) {
            return row;
        }
        ArtifactIdReservation ids = reservations.publicIds().get(artifact);
        int offset = offsets.getOrDefault(artifact, 0);
        offsets.put(artifact, offset + 1);
        return row.withValue("id", Long.toString(ids.idAt(offset)));
    }

    private ArtifactRow loadFinalRow(Connection connection,
                                     long branchId,
                                     DataframeArtifactSchema schema) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target_column, value
                FROM temp_import_final_cell
                WHERE branch_id = ?
                ORDER BY target_column
                """)) {
            statement.setLong(1, branchId);
            try (ResultSet cells = statement.executeQuery()) {
                while (cells.next()) {
                    values.put(cells.getString(1), cells.getString(2));
                }
            }
        }
        if (!values.keySet().equals(schema.columns().stream()
                .map(DataframeColumn::name).collect(java.util.stream.Collectors.toSet()))) {
            throw new IocExtractorException("Import mutation plan does not cover the public artifact schema");
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        schema.columns().forEach(column -> ordered.put(column.name(), values.get(column.name())));
        return ArtifactRow.ordered(ordered);
    }

    private List<SlotResolution> reconcilePreferredSlots(Connection connection,
                                                        StageHeader header,
                                                        List<SlotRequest> requests,
                                                        EffectiveTime asOf) throws SQLException {
        if (requests.isEmpty()) {
            return List.of();
        }
        String profile = header.slotProfile().orElseThrow(
                () -> new IocExtractorException("Requested import slot has no pinned profile"));
        ImportExistingSlotPolicy policy = header.existingSlotPolicy().orElseThrow();
        Map<String, List<PreferredExportSlotRequest>> byArtifact = new LinkedHashMap<>();
        Map<String, List<SlotRequest>> evidenceByArtifact = new LinkedHashMap<>();
        for (SlotRequest request : requests) {
            byArtifact.computeIfAbsent(request.artifact(), ignored -> new ArrayList<>())
                    .add(new PreferredExportSlotRequest(
                            request.lifecycleId(), request.requestedSlot(), request.newLifecycle(), policy));
            evidenceByArtifact.computeIfAbsent(request.artifact(), ignored -> new ArrayList<>())
                    .add(request);
        }
        List<SlotResolution> evidence = new ArrayList<>(requests.size());
        for (var entry : byArtifact.entrySet()) {
            List<PreferredExportSlotResolution> resolutions = exportSlots.reconcilePreferred(
                    connection, profile, entry.getKey(), asOf.value(), entry.getValue());
            List<SlotRequest> sources = evidenceByArtifact.get(entry.getKey());
            for (int index = 0; index < resolutions.size(); index++) {
                PreferredExportSlotResolution resolution = resolutions.get(index);
                SlotRequest source = sources.get(index);
                evidence.add(new SlotResolution(
                        source.sourceRow(), source.artifact(), resolution.lifecycleId(),
                        resolution.requestedSlot(), resolution.assignedSlot(), resolution.outcome()));
            }
        }
        return List.copyOf(evidence);
    }

    private Map<String, Long> advancePublicState(Connection connection,
                                                 Set<String> artifacts,
                                                 EffectiveTime asOf) throws SQLException {
        Map<String, Long> generations = new LinkedHashMap<>();
        for (String artifact : artifacts.stream().sorted().toList()) {
            try (PreparedStatement revision = connection.prepareStatement("""
                    INSERT INTO artifact_revision(artifact, revision, changed_at)
                    VALUES (?, 1, ?)
                    ON CONFLICT(artifact) DO UPDATE SET
                        revision = artifact_revision.revision + 1,
                        changed_at = excluded.changed_at
                    """)) {
                revision.setString(1, artifact);
                revision.setString(2, asOf.value().toString());
                revision.executeUpdate();
            }
            try (PreparedStatement generation = connection.prepareStatement("""
                    INSERT INTO artifact_projection_state(
                        artifact, required_generation, projected_generation, requested_at_ms)
                    VALUES (?, 1, 0, ?)
                    ON CONFLICT(artifact) DO UPDATE SET
                        required_generation = artifact_projection_state.required_generation + 1,
                        requested_at_ms = excluded.requested_at_ms,
                        last_error_code = NULL
                    RETURNING required_generation
                    """)) {
                generation.setString(1, artifact);
                generation.setLong(2, epochMillis(asOf));
                try (ResultSet row = generation.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Import projection generation did not advance");
                    }
                    generations.put(artifact, row.getLong(1));
                }
            }
        }
        return Map.copyOf(generations);
    }

    private void insertReceipt(Connection connection,
                               CanonicalImportCommand command,
                               CanonicalImportResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_commit(
                    delivery_id, sequence_no, observation_id, source_id,
                    snapshot_sha256, snapshot_size, contract_id, contract_version,
                    contract_fingerprint, stage_sha256, outcome, effective_as_of_ms,
                    accepted_rows, rejected_rows, public_mutations,
                    committed_at_ms, purge_after_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMMITTED', ?, ?, ?, ?, ?, ?)
                """)) {
            long committedAt = result.effectiveAt().toEpochMilli();
            statement.setString(1, command.deliveryId().value());
            statement.setLong(2, command.sequence().value());
            statement.setString(3, observationId(command));
            statement.setString(4, command.sourceId().value());
            statement.setString(5, command.snapshot().digest().value());
            statement.setLong(6, command.snapshot().size());
            statement.setString(7, command.contract().id().value());
            statement.setInt(8, command.contract().version());
            statement.setString(9, command.contract().fingerprint().value());
            statement.setString(10, command.stage().digest().value());
            statement.setLong(11, committedAt);
            statement.setLong(12, result.acceptedRows());
            statement.setLong(13, result.rejectedRows());
            statement.setLong(14, result.publicMutations());
            statement.setLong(15, committedAt);
            statement.setLong(16, result.effectiveAt().plus(receiptRetention).toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void persistRejections(Connection connection, CanonicalImportCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_row_rejection(
                    delivery_id, rejection_ordinal, source_row_number, artifact, diagnostic_code)
                SELECT ?, rejection_ordinal, source_row_number, artifact, diagnostic_code
                FROM temp_import_rejection
                ORDER BY rejection_ordinal
                """)) {
            statement.setString(1, command.deliveryId().value());
            statement.executeUpdate();
        }
    }

    private void persistSlotResolutions(Connection connection,
                                        CanonicalImportCommand command,
                                        List<SlotResolution> resolutions) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_slot_resolution(
                    delivery_id, resolution_ordinal, source_row_number, artifact,
                    lifecycle_id, requested_slot, assigned_slot, outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int ordinal = 0;
            for (SlotResolution resolution : resolutions) {
                statement.setString(1, command.deliveryId().value());
                statement.setInt(2, ++ordinal);
                statement.setLong(3, resolution.sourceRow());
                statement.setString(4, resolution.artifact());
                statement.setLong(5, resolution.lifecycleId());
                statement.setLong(6, resolution.requestedSlot());
                statement.setLong(7, resolution.assignedSlot());
                statement.setString(8, resolution.outcome().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void persistArtifactEvidence(Connection connection,
                                         CanonicalImportCommand command,
                                         CanonicalImportResult result) throws SQLException {
        Set<String> artifacts = new LinkedHashSet<>(result.observedArtifacts());
        artifacts.addAll(result.affectedArtifacts());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_commit_artifact(
                    delivery_id, artifact, public_mutation, deadline_changed, projection_generation)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (String artifact : artifacts.stream().sorted().toList()) {
                statement.setString(1, command.deliveryId().value());
                statement.setString(2, artifact);
                statement.setInt(3, result.affectedArtifacts().contains(artifact) ? 1 : 0);
                statement.setInt(4, result.observedArtifacts().contains(artifact) ? 1 : 0);
                Long generation = result.projectionGenerations().get(artifact);
                if (generation == null) {
                    statement.setObject(5, null);
                } else {
                    statement.setLong(5, generation);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Optional<CanonicalImportResult> loadCommitted(CanonicalImportCommand command) {
        try (Connection connection = dataSource.getConnection()) {
            return loadCommitted(connection, command);
        } catch (SQLException failure) {
            throw new IocExtractorException("Cannot inspect canonical import receipt", failure);
        }
    }

    private Optional<CanonicalImportResult> loadCommitted(Connection connection,
                                                           CanonicalImportCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sequence_no, observation_id, source_id, snapshot_sha256, snapshot_size,
                       contract_id, contract_version, contract_fingerprint, stage_sha256,
                       effective_as_of_ms, accepted_rows, rejected_rows, public_mutations
                FROM import_commit WHERE delivery_id = ?
                """)) {
            statement.setString(1, command.deliveryId().value());
            try (ResultSet receipt = statement.executeQuery()) {
                if (!receipt.next()) {
                    return Optional.empty();
                }
                requireReceiptIdentity(command, receipt);
                Instant effectiveAt = Instant.ofEpochMilli(receipt.getLong("effective_as_of_ms"));
                Set<String> affected = new LinkedHashSet<>();
                Set<String> observed = new LinkedHashSet<>();
                Map<String, Long> generations = new LinkedHashMap<>();
                loadArtifactEvidence(connection, command, affected, observed, generations);
                return Optional.of(new CanonicalImportResult(
                        ImportPromotionOutcome.COMMITTED,
                        receipt.getLong("accepted_rows"), receipt.getLong("rejected_rows"),
                        receipt.getLong("public_mutations"), affected, observed, generations, effectiveAt));
            }
        }
    }

    private void requireReceiptIdentity(CanonicalImportCommand command, ResultSet receipt) throws SQLException {
        boolean matches = receipt.getLong("sequence_no") == command.sequence().value()
                && observationId(command).equals(receipt.getString("observation_id"))
                && command.sourceId().value().equals(receipt.getString("source_id"))
                && command.snapshot().digest().value().equals(receipt.getString("snapshot_sha256"))
                && command.snapshot().size() == receipt.getLong("snapshot_size")
                && command.contract().id().value().equals(receipt.getString("contract_id"))
                && command.contract().version() == receipt.getInt("contract_version")
                && command.contract().fingerprint().value().equals(
                        receipt.getString("contract_fingerprint"))
                && command.stage().digest().value().equals(receipt.getString("stage_sha256"));
        if (!matches) {
            throw new IocExtractorException("Import delivery identity conflicts with canonical receipt");
        }
    }

    private void loadArtifactEvidence(Connection connection,
                                      CanonicalImportCommand command,
                                      Set<String> affected,
                                      Set<String> observed,
                                      Map<String, Long> generations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT artifact, public_mutation, deadline_changed, projection_generation
                FROM import_commit_artifact
                WHERE delivery_id = ?
                ORDER BY artifact
                """)) {
            statement.setString(1, command.deliveryId().value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String artifact = rows.getString("artifact");
                    if (rows.getInt("public_mutation") == 1) {
                        affected.add(artifact);
                        generations.put(artifact, rows.getLong("projection_generation"));
                    }
                    if (rows.getInt("deadline_changed") == 1) {
                        observed.add(artifact);
                    }
                }
            }
        }
    }

    private CanonicalImportResult replayed(CanonicalImportResult result) {
        return new CanonicalImportResult(
                ImportPromotionOutcome.ALREADY_COMMITTED,
                result.acceptedRows(), result.rejectedRows(), result.publicMutations(),
                result.affectedArtifacts(), result.observedArtifacts(),
                result.projectionGenerations(), result.effectiveAt());
    }

    private void insertRejection(Connection connection,
                                 long sourceRow,
                                 String artifact,
                                 String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO temp_import_rejection(source_row_number, artifact, diagnostic_code)
                VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, sourceRow);
            statement.setString(2, artifact);
            statement.setString(3, code);
            statement.executeUpdate();
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new SQLException("Import planning aggregate returned no row");
            }
            return row.getLong(1);
        }
    }

    private Map<String, JdbcArtifactIdAllocator> initializePublicIdAllocators(
            List<ArtifactIdAllocatorDefinition> definitions,
            Clock clock) {
        Map<String, JdbcArtifactIdAllocator> allocators = new LinkedHashMap<>();
        for (ArtifactIdAllocatorDefinition definition : Objects.requireNonNull(definitions, "publicIdDefinitions")) {
            JdbcArtifactIdAllocator allocator = new JdbcArtifactIdAllocator(dataSource, clock);
            allocator.ensureInitialized(definition);
            if (allocators.put(definition.artifact(), allocator) != null) {
                throw new IllegalArgumentException(
                        "Duplicate public id allocator definition: " + definition.artifact());
            }
        }
        return Map.copyOf(allocators);
    }

    private Map<String, DataframeArtifactSchema> schemasByName(List<DataframeArtifactSchema> source) {
        Map<String, DataframeArtifactSchema> result = new LinkedHashMap<>();
        Objects.requireNonNull(source, "schemas").stream()
                .sorted(Comparator.comparing(DataframeArtifactSchema::artifactName))
                .forEach(schema -> {
                    if (result.put(schema.artifactName(), schema) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate dataframe artifact schema: " + schema.artifactName());
                    }
                });
        return Map.copyOf(result);
    }

    private DataframeArtifactSchema requireSchema(String artifact) {
        DataframeArtifactSchema schema = schemas.get(artifact);
        if (schema == null) {
            throw new IocExtractorException("Unknown import artifact: " + artifact);
        }
        return schema;
    }

    private boolean hasPublicId(DataframeArtifactSchema schema) {
        return schema.columns().stream().anyMatch(column -> "id".equals(column.name()));
    }

    private ImportSha256 digest(Path path) {
        try {
            return ImportFileDigests.sha256(path);
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot hash sealed import stage", failure);
        }
    }

    private String immutableUri(Path path) {
        return path.toAbsolutePath().normalize().toUri().toASCIIString() + "?mode=ro&immutable=1";
    }

    private String observationId(CanonicalImportCommand command) {
        return "import:" + command.deliveryId().value();
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Optional<Long> optionalLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static void setOptionalLong(PreparedStatement statement,
                                        int index,
                                        Optional<Long> value) throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.orElseThrow());
        } else {
            statement.setObject(index, null);
        }
    }

    private record VerifiedStage(
            Path path,
            StageHeader header,
            Map<String, Integer> branchCounts) {
    }

    private record StageHeader(
            String snapshotDigest,
            long snapshotSize,
            String contractId,
            int contractVersion,
            String contractFingerprint,
            ImportRowFailurePolicy rowFailurePolicy,
            boolean renewUnchanged,
            Optional<String> slotProfile,
            Optional<ImportExistingSlotPolicy> existingSlotPolicy,
            long sourceRows,
            long acceptedRows,
            long rejectedRows) {
    }

    private record ReservedIds(
            Map<String, ArtifactIdReservation> publicIds,
            LifecycleIdReservation lifecycleIds) {
    }

    private record BranchCandidate(
            long branchId,
            long sourceRow,
            String artifact,
            String recordKeyHash,
            Optional<Long> requestedSlot,
            int matchCount,
            Optional<Long> canonicalRowId,
            Optional<Long> lifecycleId) {
    }

    private record Rejection(String artifact, String code) {
    }

    private record PlannedBranch(
            BranchCandidate candidate,
            Optional<ArtifactRow> finalRow,
            boolean renewTtl,
            Optional<Rejection> rejection) {

        private static PlannedBranch accepted(
                BranchCandidate candidate, ArtifactRow finalRow, boolean renewTtl) {
            return new PlannedBranch(candidate, Optional.of(finalRow), renewTtl, Optional.empty());
        }

        private static PlannedBranch rejected(BranchCandidate candidate, String code) {
            return new PlannedBranch(candidate, Optional.empty(), false,
                    Optional.of(new Rejection(candidate.artifact(), code)));
        }
    }

    private record PromotionCounts(long acceptedRows, long rejectedRows) {
    }

    private record SlotRequest(
            long sourceRow,
            String artifact,
            long lifecycleId,
            long requestedSlot,
            boolean newLifecycle) {
    }

    private record SlotResolution(
            long sourceRow,
            String artifact,
            long lifecycleId,
            long requestedSlot,
            long assignedSlot,
            PreferredExportSlotResolution.Outcome outcome) {
    }

    private record MutationSummary(
            long publicMutations,
            Set<String> affectedArtifacts,
            Set<String> observedArtifacts,
            List<SlotRequest> slotRequests) {
    }

    @FunctionalInterface
    interface ConnectionTimeSource {
        EffectiveTime now(Connection connection) throws SQLException;
    }
}
