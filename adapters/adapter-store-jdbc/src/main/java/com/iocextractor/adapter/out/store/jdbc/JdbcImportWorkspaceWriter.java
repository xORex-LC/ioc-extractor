package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.dataframeimport.ImportWorkspaceException;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Streaming JDBC writer and deterministic set-based duplicate finalizer. */
final class JdbcImportWorkspaceWriter implements ImportWorkspaceWriter {

    private static final String MAPPED = "MAPPED";
    private static final String REJECTED = "REJECTED";

    private final Connection connection;
    private final CreateImportWorkspaceCommand command;
    private final ImportWorkspaceLayout.WorkspacePaths paths;
    private final ImportWorkspaceLimits limits;
    private final Clock clock;
    private final Runnable growthCapacityCheck;
    private final JdbcImportWorkspace.SealOperation sealOperation;
    private final PreparedStatement insertInput;
    private final PreparedStatement insertBranch;
    private final PreparedStatement insertCell;
    private final PreparedStatement insertMatchKey;
    private final PreparedStatement insertError;

    private long sourceRows;
    private long rowErrors;
    private long lastSourceRow;
    private int pendingRows;
    private boolean closed;
    private boolean sealed;

    JdbcImportWorkspaceWriter(Connection connection,
                              CreateImportWorkspaceCommand command,
                              ImportWorkspaceLayout.WorkspacePaths paths,
                              ImportWorkspaceLimits limits,
                              Clock clock,
                              Runnable growthCapacityCheck,
                              JdbcImportWorkspace.SealOperation sealOperation) throws SQLException {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.command = Objects.requireNonNull(command, "command");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.growthCapacityCheck = Objects.requireNonNull(growthCapacityCheck, "growthCapacityCheck");
        this.sealOperation = Objects.requireNonNull(sealOperation, "sealOperation");
        this.insertInput = connection.prepareStatement("""
                INSERT INTO stage_input_row(
                    source_row_number, group_key_hash, group_key_canonical, status, error_count)
                VALUES (?, ?, ?, ?, ?)
                """);
        this.insertBranch = connection.prepareStatement("""
                INSERT INTO stage_branch(
                    source_row_number, branch_ordinal, artifact, primary_flag,
                    requested_slot, record_definition_id, record_key_hash,
                    record_key_canonical, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
        this.insertCell = connection.prepareStatement("""
                INSERT INTO stage_cell(branch_id, target_column, merge_policy, presence, value)
                VALUES (?, ?, ?, ?, ?)
                """);
        this.insertMatchKey = connection.prepareStatement("""
                INSERT INTO stage_match_key(branch_id, definition_id, key_hash, key_canonical)
                VALUES (?, ?, ?, ?)
                """);
        this.insertError = connection.prepareStatement("""
                INSERT INTO stage_row_error(
                    logical_group_id, source_row_number, artifact, diagnostic_code)
                VALUES (?, ?, ?, ?)
                """);
    }

    @Override
    public void append(ImportLogicalRow row) {
        Objects.requireNonNull(row, "row");
        checkOpen();
        requireNextRow(row.sourceRowNumber());
        requireRowShape(row);
        CanonicalKeyMaterial groupKey = row.branches().stream()
                .filter(branch -> branch.role() == ImportArtifactRole.PRIMARY)
                .findFirst().orElseThrow().recordKey()
                .orElseThrow(() -> new IllegalArgumentException("Mapped import row requires a primary record key"));
        try {
            insertInput(row.sourceRowNumber(), groupKey.keyHash(), groupKey.keyCanonical(), MAPPED, 0);
            for (int ordinal = 0; ordinal < row.branches().size(); ordinal++) {
                insertBranch(row.sourceRowNumber(), ordinal, row.branches().get(ordinal));
            }
            appended(row.sourceRowNumber(), 0);
        } catch (SQLException | RuntimeException failure) {
            throw failureAfterAbort(classifyFailure("Cannot append mapped import row", failure));
        }
    }

    @Override
    public void reject(ImportRejectedLogicalRow row) {
        Objects.requireNonNull(row, "row");
        checkOpen();
        requireNextRow(row.sourceRowNumber());
        requireErrorCapacity(row.issues().size());
        try {
            insertInput(row.sourceRowNumber(), null, null, REJECTED, row.issues().size());
            for (ImportRowIssue issue : row.issues()) {
                insertError(row.sourceRowNumber(), issue);
            }
            appended(row.sourceRowNumber(), row.issues().size());
        } catch (SQLException | RuntimeException failure) {
            throw failureAfterAbort(classifyFailure("Cannot append rejected import row", failure));
        }
    }

    @Override
    public ImportStage seal() {
        checkOpen();
        try {
            commitBatch();
            ImportWorkspaceSchema.createSealIndexes(connection);
            if (command.duplicatePolicy() == ImportDuplicatePolicy.COALESCE) {
                coalesceDuplicates();
            } else {
                keepFirstDuplicates();
            }
            finalizeStatuses();
            long acceptedRows = countStatus("ACCEPTED");
            long rejectedRows = countLogicalRejections();
            updateMeta(acceptedRows, rejectedRows);
            connection.commit();
            closeStatements();
            connection.setAutoCommit(true);
            checkpointAndVerify();
            connection.close();
            closed = true;
            ImportStage stage = sealOperation.seal(paths, sourceRows, acceptedRows, rejectedRows);
            sealed = true;
            return stage;
        } catch (SQLException | RuntimeException failure) {
            throw failureAfterAbort(classifyFailure("Cannot seal import workspace", failure));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        try {
            if (!sealed) {
                connection.rollback();
            }
        } catch (SQLException rollbackFailure) {
            failure = storageFailure("Cannot roll back unsealed import workspace", rollbackFailure);
        }
        try {
            closeStatements();
            connection.close();
        } catch (SQLException closeFailure) {
            if (failure == null) {
                failure = storageFailure("Cannot close import workspace", closeFailure);
            } else {
                failure.addSuppressed(closeFailure);
            }
        } finally {
            closed = true;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void insertInput(long sourceRowNumber,
                             String groupHash,
                             String groupCanonical,
                             String status,
                             int errorCount) throws SQLException {
        insertInput.setLong(1, sourceRowNumber);
        insertInput.setString(2, groupHash);
        insertInput.setString(3, groupCanonical);
        insertInput.setString(4, status);
        insertInput.setInt(5, errorCount);
        insertInput.executeUpdate();
    }

    private void insertBranch(long sourceRowNumber,
                              int ordinal,
                              ImportArtifactBranch branch) throws SQLException {
        CanonicalKeyMaterial recordKey = branch.recordKey().orElseThrow();
        insertBranch.setLong(1, sourceRowNumber);
        insertBranch.setInt(2, ordinal);
        insertBranch.setString(3, branch.artifactName());
        insertBranch.setInt(4, branch.role() == ImportArtifactRole.PRIMARY ? 1 : 0);
        if (branch.requestedSlot().isPresent()) {
            insertBranch.setLong(5, branch.requestedSlot().getAsLong());
        } else {
            insertBranch.setObject(5, null);
        }
        insertBranch.setString(6, recordKey.definitionId());
        insertBranch.setString(7, recordKey.keyHash());
        insertBranch.setString(8, recordKey.keyCanonical());
        insertBranch.setString(9, MAPPED);
        insertBranch.executeUpdate();
        long branchId;
        try (ResultSet generated = insertBranch.getGeneratedKeys()) {
            if (!generated.next()) {
                throw new SQLException("Import staging branch insert returned no identity");
            }
            branchId = generated.getLong(1);
        }
        branch.cells().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> addCell(
                        branchId, entry.getKey(), entry.getValue(), branch.mergePolicies().get(entry.getKey())));
        insertCell.executeBatch();
        branch.matchKeys().stream().sorted(Comparator.comparing(CanonicalKeyMaterial::definitionId))
                .forEach(key -> addMatchKey(branchId, key));
        insertMatchKey.executeBatch();
    }

    private void addCell(long branchId,
                         String target,
                         ImportCell cell,
                         com.iocextractor.application.dataframeimport.model.ImportMergePolicy mergePolicy) {
        try {
            insertCell.setLong(1, branchId);
            insertCell.setString(2, target);
            insertCell.setString(3, mergePolicy.name());
            insertCell.setInt(4, cell.presence().ordinal());
            insertCell.setString(5, cell.value());
            insertCell.addBatch();
        } catch (SQLException failure) {
            throw storageFailure("Cannot prepare import staging cell", failure);
        }
    }

    private void addMatchKey(long branchId, CanonicalKeyMaterial key) {
        try {
            insertMatchKey.setLong(1, branchId);
            insertMatchKey.setString(2, key.definitionId());
            insertMatchKey.setString(3, key.keyHash());
            insertMatchKey.setString(4, key.keyCanonical());
            insertMatchKey.addBatch();
        } catch (SQLException failure) {
            throw storageFailure("Cannot prepare import staging match key", failure);
        }
    }

    private void insertError(long logicalGroupId, ImportRowIssue issue) throws SQLException {
        insertError.setLong(1, logicalGroupId);
        insertError.setLong(2, issue.sourceRowNumber());
        insertError.setString(3, issue.artifact());
        insertError.setString(4, issue.code());
        insertError.executeUpdate();
    }

    private void appended(long sourceRowNumber, int errors) throws SQLException {
        sourceRows++;
        rowErrors += errors;
        lastSourceRow = sourceRowNumber;
        pendingRows++;
        if (pendingRows >= limits.transactionBatchRows()) {
            commitBatch();
        }
    }

    private void commitBatch() throws SQLException {
        if (pendingRows == 0) {
            return;
        }
        connection.commit();
        pendingRows = 0;
        requireStageSize();
        growthCapacityCheck.run();
    }

    private void keepFirstDuplicates() throws SQLException {
        long duplicateRows = queryLong("""
                SELECT COUNT(*)
                FROM stage_input_row current
                WHERE status = 'MAPPED'
                  AND source_row_number <> (
                      SELECT MIN(first.source_row_number)
                      FROM stage_input_row first
                      WHERE first.status = 'MAPPED'
                        AND first.group_key_hash = current.group_key_hash
                        AND first.group_key_canonical = current.group_key_canonical)
                """);
        requireErrorCapacity(duplicateRows);
        execute("""
                INSERT INTO stage_row_error(
                    logical_group_id, source_row_number, artifact, diagnostic_code)
                SELECT (
                           SELECT MIN(first.source_row_number)
                           FROM stage_input_row first
                           WHERE first.status = 'MAPPED'
                             AND first.group_key_hash = current.group_key_hash
                             AND first.group_key_canonical = current.group_key_canonical),
                       current.source_row_number, primary_branch.artifact,
                       'IMPORT.DUPLICATE_IGNORED'
                FROM stage_input_row current
                JOIN stage_branch primary_branch
                  ON primary_branch.source_row_number = current.source_row_number
                 AND primary_branch.primary_flag = 1
                WHERE current.status = 'MAPPED'
                  AND current.source_row_number <> (
                      SELECT MIN(first.source_row_number)
                      FROM stage_input_row first
                      WHERE first.status = 'MAPPED'
                        AND first.group_key_hash = current.group_key_hash
                        AND first.group_key_canonical = current.group_key_canonical)
                """);
        execute("""
                UPDATE stage_input_row AS current
                SET status = 'DUPLICATE_IGNORED', error_count = error_count + 1
                WHERE status = 'MAPPED'
                  AND source_row_number <> (
                      SELECT MIN(first.source_row_number)
                      FROM stage_input_row first
                      WHERE first.status = 'MAPPED'
                        AND first.group_key_hash = current.group_key_hash
                        AND first.group_key_canonical = current.group_key_canonical)
                """);
        rowErrors += duplicateRows;
    }

    private void coalesceDuplicates() throws SQLException {
        long duplicateGroups = queryLong("""
                SELECT COUNT(*)
                FROM (
                    SELECT group_key_hash, group_key_canonical
                    FROM stage_input_row
                    WHERE status = 'MAPPED'
                    GROUP BY group_key_hash, group_key_canonical
                    HAVING COUNT(*) > 1)
                """);
        if (duplicateGroups == 0) {
            return;
        }
        execute("""
                CREATE TEMP TABLE duplicate_conflict_group (
                    group_key_hash TEXT NOT NULL,
                    group_key_canonical TEXT NOT NULL,
                    PRIMARY KEY(group_key_hash, group_key_canonical))
                """);
        execute("""
                INSERT OR IGNORE INTO duplicate_conflict_group(group_key_hash, group_key_canonical)
                SELECT input.group_key_hash, input.group_key_canonical
                FROM stage_input_row input
                JOIN stage_branch branch ON branch.source_row_number = input.source_row_number
                WHERE input.status = 'MAPPED'
                GROUP BY input.group_key_hash, input.group_key_canonical, branch.branch_ordinal
                HAVING COUNT(DISTINCT branch.requested_slot) > 1
                """);
        execute("""
                INSERT OR IGNORE INTO duplicate_conflict_group(group_key_hash, group_key_canonical)
                SELECT input.group_key_hash, input.group_key_canonical
                FROM stage_input_row input
                JOIN stage_branch branch ON branch.source_row_number = input.source_row_number
                JOIN stage_cell cell ON cell.branch_id = branch.branch_id
                WHERE input.status = 'MAPPED'
                GROUP BY input.group_key_hash, input.group_key_canonical,
                         branch.branch_ordinal, cell.target_column
                HAVING COUNT(DISTINCT CASE WHEN cell.presence <> 0 THEN cell.presence END) > 1
                    OR COUNT(DISTINCT CASE WHEN cell.presence = 2 THEN cell.value END) > 1
                """);
        long conflictRows = queryLong("""
                SELECT COUNT(*)
                FROM stage_input_row input
                JOIN duplicate_conflict_group conflict
                  ON conflict.group_key_hash = input.group_key_hash
                 AND conflict.group_key_canonical = input.group_key_canonical
                WHERE input.status = 'MAPPED'
                """);
        requireErrorCapacity(conflictRows);
        execute("""
                INSERT INTO stage_row_error(
                    logical_group_id, source_row_number, artifact, diagnostic_code)
                SELECT (
                           SELECT MIN(member.source_row_number)
                           FROM stage_input_row member
                           WHERE member.status = 'MAPPED'
                             AND member.group_key_hash = input.group_key_hash
                             AND member.group_key_canonical = input.group_key_canonical),
                       input.source_row_number, primary_branch.artifact,
                       'IMPORT.DUPLICATE_CONFLICT'
                FROM stage_input_row input
                JOIN duplicate_conflict_group conflict
                  ON conflict.group_key_hash = input.group_key_hash
                 AND conflict.group_key_canonical = input.group_key_canonical
                JOIN stage_branch primary_branch
                  ON primary_branch.source_row_number = input.source_row_number
                 AND primary_branch.primary_flag = 1
                WHERE input.status = 'MAPPED'
                """);
        execute("""
                UPDATE stage_input_row AS input
                SET status = 'REJECTED', error_count = error_count + 1
                WHERE status = 'MAPPED'
                  AND EXISTS (
                      SELECT 1 FROM duplicate_conflict_group conflict
                      WHERE conflict.group_key_hash = input.group_key_hash
                        AND conflict.group_key_canonical = input.group_key_canonical)
                """);
        rowErrors += conflictRows;
        materializeCompatibleCoalesce();
    }

    private void materializeCompatibleCoalesce() throws SQLException {
        execute("""
                CREATE TEMP TABLE representative_branch AS
                SELECT input.group_key_hash, input.group_key_canonical,
                       branch.branch_ordinal, branch.branch_id
                FROM stage_input_row input
                JOIN stage_branch branch ON branch.source_row_number = input.source_row_number
                WHERE input.status = 'MAPPED'
                  AND input.source_row_number = (
                      SELECT MIN(first.source_row_number)
                      FROM stage_input_row first
                      WHERE first.status = 'MAPPED'
                        AND first.group_key_hash = input.group_key_hash
                        AND first.group_key_canonical = input.group_key_canonical)
                """);
        execute("""
                CREATE UNIQUE INDEX ux_representative_branch
                ON representative_branch(group_key_hash, group_key_canonical, branch_ordinal)
                """);
        execute("""
                CREATE UNIQUE INDEX ux_representative_branch_id
                ON representative_branch(branch_id)
                """);
        execute("""
                CREATE TEMP TABLE coalesced_cell AS
                SELECT input.group_key_hash, input.group_key_canonical,
                       branch.branch_ordinal, cell.target_column,
                       MAX(cell.presence) AS presence,
                       MAX(CASE WHEN cell.presence = 2 THEN cell.value END) AS value
                FROM stage_input_row input
                JOIN stage_branch branch ON branch.source_row_number = input.source_row_number
                JOIN stage_cell cell ON cell.branch_id = branch.branch_id
                WHERE input.status = 'MAPPED'
                GROUP BY input.group_key_hash, input.group_key_canonical,
                         branch.branch_ordinal, cell.target_column
                """);
        execute("""
                CREATE UNIQUE INDEX ux_coalesced_cell
                ON coalesced_cell(
                    group_key_hash, group_key_canonical, branch_ordinal, target_column)
                """);
        execute("""
                UPDATE stage_cell AS target
                SET presence = (
                        SELECT source.presence
                        FROM representative_branch representative
                        JOIN coalesced_cell source
                          ON source.group_key_hash = representative.group_key_hash
                         AND source.group_key_canonical = representative.group_key_canonical
                         AND source.branch_ordinal = representative.branch_ordinal
                        WHERE representative.branch_id = target.branch_id
                          AND source.target_column = target.target_column),
                    value = (
                        SELECT source.value
                        FROM representative_branch representative
                        JOIN coalesced_cell source
                          ON source.group_key_hash = representative.group_key_hash
                         AND source.group_key_canonical = representative.group_key_canonical
                         AND source.branch_ordinal = representative.branch_ordinal
                        WHERE representative.branch_id = target.branch_id
                          AND source.target_column = target.target_column)
                WHERE branch_id IN (SELECT branch_id FROM representative_branch)
                """);
        execute("""
                UPDATE stage_branch AS target
                SET requested_slot = (
                    SELECT MAX(source_branch.requested_slot)
                    FROM representative_branch representative
                    JOIN stage_input_row member
                      ON member.group_key_hash = representative.group_key_hash
                     AND member.group_key_canonical = representative.group_key_canonical
                     AND member.status = 'MAPPED'
                    JOIN stage_branch source_branch
                      ON source_branch.source_row_number = member.source_row_number
                     AND source_branch.branch_ordinal = representative.branch_ordinal
                    WHERE representative.branch_id = target.branch_id)
                WHERE branch_id IN (SELECT branch_id FROM representative_branch)
                """);
        execute("""
                INSERT OR IGNORE INTO stage_match_key(branch_id, definition_id, key_hash, key_canonical)
                SELECT representative.branch_id, match.definition_id, match.key_hash, match.key_canonical
                FROM representative_branch representative
                JOIN stage_input_row member
                  ON member.group_key_hash = representative.group_key_hash
                 AND member.group_key_canonical = representative.group_key_canonical
                 AND member.status = 'MAPPED'
                JOIN stage_branch source_branch
                  ON source_branch.source_row_number = member.source_row_number
                 AND source_branch.branch_ordinal = representative.branch_ordinal
                JOIN stage_match_key match ON match.branch_id = source_branch.branch_id
                """);
        execute("""
                UPDATE stage_input_row AS input
                SET status = 'COALESCED'
                WHERE status = 'MAPPED'
                  AND source_row_number <> (
                      SELECT MIN(first.source_row_number)
                      FROM stage_input_row first
                      WHERE first.status = 'MAPPED'
                        AND first.group_key_hash = input.group_key_hash
                        AND first.group_key_canonical = input.group_key_canonical)
                """);
    }

    private void finalizeStatuses() throws SQLException {
        execute("UPDATE stage_input_row SET status = 'ACCEPTED' WHERE status = 'MAPPED'");
        execute("""
                UPDATE stage_branch
                SET status = (
                    SELECT input.status
                    FROM stage_input_row input
                    WHERE input.source_row_number = stage_branch.source_row_number)
                """);
    }

    private void updateMeta(long acceptedRows, long rejectedRows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stage_meta
                SET source_row_count = ?, logical_row_count = ?,
                    accepted_count = ?, rejected_count = ?, sealed_at_ms = ?
                WHERE delivery_id = ? AND sealed_at_ms IS NULL
                """)) {
            statement.setLong(1, sourceRows);
            statement.setLong(2, acceptedRows + rejectedRows);
            statement.setLong(3, acceptedRows);
            statement.setLong(4, rejectedRows);
            statement.setLong(5, clock.instant().toEpochMilli());
            statement.setString(6, command.deliveryId().value());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Import staging metadata is missing or already sealed");
            }
        }
    }

    private void checkpointAndVerify() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            try (ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
                if (!resultSet.next() || !"ok".equals(resultSet.getString(1))) {
                    throw new SQLException("Import staging SQLite integrity check failed");
                }
            }
        }
        requireStageSize();
        growthCapacityCheck.run();
    }

    private long countStatus(String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM stage_input_row WHERE status = ?")) {
            statement.setString(1, status);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Import staging count query returned no row");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private long countLogicalRejections() throws SQLException {
        return queryLong("""
                SELECT
                    (SELECT COUNT(*)
                     FROM stage_input_row
                     WHERE status = 'REJECTED' AND group_key_hash IS NULL)
                  + (SELECT COUNT(*)
                     FROM (
                         SELECT group_key_hash, group_key_canonical
                         FROM stage_input_row
                         WHERE status = 'REJECTED' AND group_key_hash IS NOT NULL
                         GROUP BY group_key_hash, group_key_canonical))
                """);
    }

    private long queryLong(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SQLException("Import staging aggregate query returned no row");
            }
            return resultSet.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void requireNextRow(long sourceRowNumber) {
        if (sourceRows >= limits.maximumSourceRows()) {
            throw limitFailure("Import stage exceeds the configured source-row limit");
        }
        if (sourceRowNumber <= lastSourceRow) {
            throw new IllegalArgumentException("Import source rows must be appended in strictly increasing order");
        }
    }

    private void requireRowShape(ImportLogicalRow row) {
        if (row.branches().size() > limits.maximumBranchesPerRow()) {
            throw limitFailure("Import row exceeds the configured branch limit");
        }
        if (row.branches().stream().anyMatch(
                branch -> branch.cells().size() > limits.maximumCellsPerBranch())) {
            throw limitFailure("Import branch exceeds the configured cell limit");
        }
    }

    private void requireErrorCapacity(long additional) {
        if (additional < 0 || rowErrors + additional > limits.maximumRowErrors()) {
            throw limitFailure("Import stage exceeds the configured row-error limit");
        }
    }

    private void requireStageSize() {
        long bytes = size(paths.building()) + size(sidecar("-wal")) + size(sidecar("-shm"));
        if (bytes > limits.maximumStageBytes()) {
            throw limitFailure("Import stage exceeds the configured byte limit");
        }
    }

    private Path sidecar(String suffix) {
        return paths.building().resolveSibling(paths.building().getFileName() + suffix);
    }

    private long size(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException failure) {
            throw storageFailure("Cannot inspect import stage size", failure);
        }
    }

    private void checkOpen() {
        if (closed || sealed) {
            throw new IllegalStateException("Import workspace writer is closed or sealed");
        }
    }

    private void closeStatements() throws SQLException {
        insertInput.close();
        insertBranch.close();
        insertCell.close();
        insertMatchKey.close();
        insertError.close();
    }

    private RuntimeException failureAfterAbort(RuntimeException primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
        try {
            closeStatements();
        } catch (SQLException statementFailure) {
            primary.addSuppressed(statementFailure);
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            primary.addSuppressed(closeFailure);
        } finally {
            closed = true;
        }
        return primary;
    }

    private RuntimeException classifyFailure(String message, Exception failure) {
        if (failure instanceof SQLException sqlFailure) {
            return storageFailure(message, sqlFailure);
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        return new IllegalStateException("Unexpected checked workspace failure", failure);
    }

    private ImportWorkspaceException limitFailure(String message) {
        return new ImportWorkspaceException(
                ImportWorkspaceException.Reason.HARD_LIMIT_EXCEEDED, message);
    }

    private ImportWorkspaceException storageFailure(String message, Throwable cause) {
        return new ImportWorkspaceException(
                ImportWorkspaceException.Reason.STORAGE_FAILURE, message, cause);
    }
}
