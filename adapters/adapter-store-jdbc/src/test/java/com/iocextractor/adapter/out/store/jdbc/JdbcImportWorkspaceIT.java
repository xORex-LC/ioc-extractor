package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceCapacity;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.dataframeimport.ImportWorkspaceException;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JdbcImportWorkspaceIT {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void coalescesCompatibleTriStateRowsAndVerifiesSealedStageReadOnly() throws Exception {
        JdbcImportWorkspace workspace = workspace(limits(1_000));
        CreateImportWorkspaceCommand command = command("coalesce", ImportDuplicatePolicy.COALESCE);
        ImportStage stage;
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.append(row(2, "key-a", Map.of(
                    "ip", ImportCell.value("192.0.2.1"),
                    "description", ImportCell.absent())));
            writer.append(row(3, "key-a", Map.of(
                    "description", ImportCell.value("malicious"),
                    "ip", ImportCell.value("192.0.2.1"))));
            stage = writer.seal();
        }

        assertThat(stage.sourceRows()).isEqualTo(2);
        assertThat(stage.acceptedRows()).isOne();
        assertThat(stage.rejectedRows()).isZero();
        assertThat(workspace.verifySealed(command, stage)).isEqualTo(stage);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sealedFile());
             var resultSet = connection.createStatement().executeQuery("""
                     SELECT input.status, cell.presence, cell.value
                     FROM stage_input_row input
                     JOIN stage_branch branch ON branch.source_row_number = input.source_row_number
                     JOIN stage_cell cell ON cell.branch_id = branch.branch_id
                     WHERE input.source_row_number = 2 AND cell.target_column = 'description'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("status")).isEqualTo("ACCEPTED");
            assertThat(resultSet.getInt("presence")).isEqualTo(ImportCell.Presence.VALUE.ordinal());
            assertThat(resultSet.getString("value")).isEqualTo("malicious");
        }
    }

    @Test
    void conflictingDuplicateGroupRejectsEveryMemberWithoutRawErrorValues() throws Exception {
        JdbcImportWorkspace workspace = workspace(limits(1_000));
        CreateImportWorkspaceCommand command = command("conflict", ImportDuplicatePolicy.COALESCE);
        ImportStage stage;
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.append(row(2, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
            writer.append(row(3, "key-a", Map.of("ip", ImportCell.value("192.0.2.2"))));
            stage = writer.seal();
        }

        assertThat(stage.acceptedRows()).isZero();
        assertThat(stage.rejectedRows()).isOne();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sealedFile());
             var resultSet = connection.createStatement().executeQuery("""
                     SELECT diagnostic_code, COUNT(*) AS issue_count,
                            COUNT(DISTINCT logical_group_id) AS group_count,
                            MIN(logical_group_id) AS group_id
                     FROM stage_row_error
                     GROUP BY diagnostic_code
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("diagnostic_code")).isEqualTo("IMPORT.DUPLICATE_CONFLICT");
            assertThat(resultSet.getLong("issue_count")).isEqualTo(2);
            assertThat(resultSet.getLong("group_count")).isOne();
            assertThat(resultSet.getLong("group_id")).isEqualTo(2);
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void keepFirstRetainsSmallestPhysicalRowAndReportsLaterDuplicates() throws Exception {
        JdbcImportWorkspace workspace = workspace(limits(1_000));
        CreateImportWorkspaceCommand command = command("keep-first", ImportDuplicatePolicy.KEEP_FIRST);
        ImportStage stage;
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.append(row(7, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
            writer.append(row(9, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
            stage = writer.seal();
        }

        assertThat(stage.acceptedRows()).isOne();
        assertThat(stage.rejectedRows()).isZero();
        assertThat(queryString("""
                SELECT group_concat(status, ',')
                FROM (SELECT status FROM stage_input_row ORDER BY source_row_number)
                """))
                .isEqualTo("ACCEPTED,DUPLICATE_IGNORED");
        assertThat(queryLong("SELECT COUNT(*) FROM stage_row_error WHERE diagnostic_code = 'IMPORT.DUPLICATE_IGNORED'"))
                .isOne();
    }

    @Test
    void coalesceRejectsConflictingRequestedSlotsButAcceptsAbsentPlusOneRequest() throws Exception {
        JdbcImportWorkspace conflicting = workspace(tempDir.resolve("slot-conflict"), limits(10));
        CreateImportWorkspaceCommand conflictCommand = command(
                "slot-conflict", ImportDuplicatePolicy.COALESCE);
        ImportStage conflictStage;
        try (ImportWorkspaceWriter writer = conflicting.create(conflictCommand)) {
            writer.append(row(2, "key-a", Map.of("ip", ImportCell.value("192.0.2.1")), 17));
            writer.append(row(3, "key-a", Map.of("ip", ImportCell.value("192.0.2.1")), 18));
            conflictStage = writer.seal();
        }
        assertThat(conflictStage.acceptedRows()).isZero();
        assertThat(conflictStage.rejectedRows()).isOne();

        Path compatibleRoot = tempDir.resolve("slot-compatible");
        JdbcImportWorkspace compatible = workspace(compatibleRoot, limits(10));
        CreateImportWorkspaceCommand compatibleCommand = command(
                "slot-compatible", ImportDuplicatePolicy.COALESCE);
        ImportStage compatibleStage;
        try (ImportWorkspaceWriter writer = compatible.create(compatibleCommand)) {
            writer.append(row(2, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
            writer.append(row(3, "key-a", Map.of("ip", ImportCell.value("192.0.2.1")), 17));
            compatibleStage = writer.seal();
        }
        assertThat(compatibleStage.acceptedRows()).isOne();
        assertThat(queryLong(sealedFile(compatibleRoot), """
                SELECT requested_slot
                FROM stage_branch branch
                JOIN stage_input_row input ON input.source_row_number = branch.source_row_number
                WHERE input.status = 'ACCEPTED'
                """)).isEqualTo(17);
    }

    @Test
    void coalesce_result_is_independent_of_compatible_row_and_column_order() throws Exception {
        Path firstRoot = tempDir.resolve("first");
        Path secondRoot = tempDir.resolve("second");
        CreateImportWorkspaceCommand firstCommand = command("permutation-a", ImportDuplicatePolicy.COALESCE);
        CreateImportWorkspaceCommand secondCommand = command("permutation-b", ImportDuplicatePolicy.COALESCE);
        JdbcImportWorkspace first = workspace(firstRoot, limits(10));
        JdbcImportWorkspace second = workspace(secondRoot, limits(10));

        try (ImportWorkspaceWriter writer = first.create(firstCommand)) {
            writer.append(row(2, "key-a", Map.of(
                    "ip", ImportCell.value("192.0.2.1"), "description", ImportCell.absent())));
            writer.append(row(3, "key-a", Map.of(
                    "description", ImportCell.value("malicious"), "ip", ImportCell.value("192.0.2.1"))));
            writer.seal();
        }
        try (ImportWorkspaceWriter writer = second.create(secondCommand)) {
            writer.append(row(2, "key-a", Map.of(
                    "description", ImportCell.value("malicious"), "ip", ImportCell.value("192.0.2.1"))));
            writer.append(row(3, "key-a", Map.of(
                    "ip", ImportCell.value("192.0.2.1"), "description", ImportCell.absent())));
            writer.seal();
        }

        assertThat(acceptedCells(sealedFile(firstRoot)))
                .isEqualTo(acceptedCells(sealedFile(secondRoot)))
                .containsExactly("description=2:malicious", "ip=2:192.0.2.1");
    }

    @Test
    void explicitRebuildRecoversUnsealedScratchAndDigestVerificationFailsClosed() {
        JdbcImportWorkspace workspace = workspace(limits(1_000));
        CreateImportWorkspaceCommand command = command("rebuild", ImportDuplicatePolicy.COALESCE);
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.append(row(2, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
        }
        assertThatThrownBy(() -> workspace.create(command))
                .isInstanceOfSatisfying(ImportWorkspaceException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportWorkspaceException.Reason.INCOMPATIBLE_EXISTING_STAGE));

        JdbcImportWorkspace recovered = workspace(limits(1_000));
        ImportStage stage;
        try (ImportWorkspaceWriter writer = recovered.rebuild(command)) {
            writer.reject(new ImportRejectedLogicalRow(2, List.of(
                    new ImportRowIssue(2, "ip_list", "IMPORT.INVALID_VALUE"))));
            stage = writer.seal();
        }
        assertThat(workspace(limits(1_000)).verifySealed(command, stage)).isEqualTo(stage);
        ImportStage corruptedEvidence = new ImportStage(
                stage.reference(), new ImportSha256("f".repeat(64)),
                stage.sourceRows(), stage.acceptedRows(), stage.rejectedRows());
        assertThatThrownBy(() -> recovered.verifySealed(command, corruptedEvidence))
                .isInstanceOfSatisfying(ImportWorkspaceException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportWorkspaceException.Reason.STAGE_INTEGRITY_FAILED));
    }

    @Test
    void adoptsOnlySealedStageWhoseMetadataMatchesPinnedRecoveryEvidence() {
        JdbcImportWorkspace workspace = workspace(limits(1_000));
        CreateImportWorkspaceCommand command = command("adopt", ImportDuplicatePolicy.COALESCE);

        assertThat(workspace.adoptSealed(
                command.deliveryId(), command.snapshot(), command.contract())).isEmpty();

        ImportStage stage;
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.append(row(2, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
            stage = writer.seal();
        }

        assertThat(workspace.adoptSealed(
                command.deliveryId(), command.snapshot(), command.contract())).contains(stage);

        List<AdoptionEvidence> mismatches = List.of(
                new AdoptionEvidence(new ImportSnapshot(
                        command.snapshot().reference(), new ImportSha256("c".repeat(64)),
                        command.snapshot().size()), command.contract()),
                new AdoptionEvidence(new ImportSnapshot(
                        command.snapshot().reference(), command.snapshot().digest(),
                        command.snapshot().size() + 1), command.contract()),
                new AdoptionEvidence(command.snapshot(), new ImportContractPin(
                        new ImportContractId("other-v1"), command.contract().version(),
                        command.contract().fingerprint())),
                new AdoptionEvidence(command.snapshot(), new ImportContractPin(
                        command.contract().id(), command.contract().version() + 1,
                        command.contract().fingerprint())),
                new AdoptionEvidence(command.snapshot(), new ImportContractPin(
                        command.contract().id(), command.contract().version(),
                        new ImportContractFingerprint("c".repeat(64)))));
        for (AdoptionEvidence mismatch : mismatches) {
            assertThatThrownBy(() -> workspace.adoptSealed(
                    command.deliveryId(), mismatch.snapshot(), mismatch.contract()))
                    .isInstanceOfSatisfying(ImportWorkspaceException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(
                                    ImportWorkspaceException.Reason.STAGE_INTEGRITY_FAILED));
        }
    }

    @Test
    void adoptionRejectsNonRegularSealedPath() throws Exception {
        JdbcImportWorkspace workspace = workspace(limits(1_000));
        CreateImportWorkspaceCommand command = command("adopt-directory", ImportDuplicatePolicy.COALESCE);
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.seal();
        }
        Path sealed = sealedFile();
        Files.delete(sealed);
        Files.createDirectory(sealed);

        assertThatThrownBy(() -> workspace.adoptSealed(
                command.deliveryId(), command.snapshot(), command.contract()))
                .isInstanceOfSatisfying(ImportWorkspaceException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportWorkspaceException.Reason.STAGE_INTEGRITY_FAILED));
    }

    @Test
    void discardRemovesScratchAndSealedStateAndAllowsExactRebuild() {
        JdbcImportWorkspace workspace = workspace(limits(1_000));
        CreateImportWorkspaceCommand command = command("discard", ImportDuplicatePolicy.COALESCE);
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.append(row(2, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
        }

        workspace.discard(command.deliveryId());
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.seal();
        }
        assertThatThrownBy(() -> workspace.create(command))
                .isInstanceOfSatisfying(ImportWorkspaceException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportWorkspaceException.Reason.INCOMPATIBLE_EXISTING_STAGE));

        workspace.discard(command.deliveryId());
        workspace.discard(command.deliveryId());
        assertThat(workspace.adoptSealed(
                command.deliveryId(), command.snapshot(), command.contract())).isEmpty();
    }

    @Test
    void capacityDistinguishesHardExhaustionAndRecoversAfterBytesAreReleased() throws Exception {
        Path root = tempDir.resolve("exhausted");
        ImportWorkspaceLimits tiny = new ImportWorkspaceLimits(
                10, 2, 4, 10, 1, 2, 1, 0, 1, DelimitedInputLimits.defaults());
        JdbcImportWorkspace workspace = workspace(root, tiny);
        Files.createDirectories(root);
        Path occupied = Files.write(root.resolve("occupied"), new byte[]{1, 2});

        assertThat(workspace.capacity().state()).isEqualTo(ImportWorkspaceCapacity.State.EXHAUSTED);
        assertThatThrownBy(() -> workspace.create(command("capacity-hard", ImportDuplicatePolicy.COALESCE)))
                .isInstanceOfSatisfying(ImportWorkspaceException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportWorkspaceException.Reason.HARD_LIMIT_EXCEEDED));

        Files.delete(occupied);
        assertThat(workspace.capacity().state()).isEqualTo(ImportWorkspaceCapacity.State.ACCEPTING);
    }

    @Test
    void hardRowLimitAndSharedCapacityWatermarkFailBeforeUnboundedGrowth() {
        ImportWorkspaceLimits oneRow = limits(1);
        JdbcImportWorkspace workspace = workspace(oneRow);
        CreateImportWorkspaceCommand command = command("limited", ImportDuplicatePolicy.COALESCE);
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            writer.append(row(2, "key-a", Map.of("ip", ImportCell.value("192.0.2.1"))));
            assertThatThrownBy(() -> writer.append(
                    row(3, "key-b", Map.of("ip", ImportCell.value("192.0.2.2")))))
                    .isInstanceOfSatisfying(ImportWorkspaceException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(
                                    ImportWorkspaceException.Reason.HARD_LIMIT_EXCEEDED));
        }

        ImportWorkspaceLimits watermark = new ImportWorkspaceLimits(
                10, 2, 4, 10, 10_000_000, 20_000_000,
                1, 0, 1, DelimitedInputLimits.defaults());
        JdbcImportWorkspace paused = new JdbcImportWorkspace(tempDir.resolve("paused"), watermark, CLOCK);
        try (ImportWorkspaceWriter ignored = paused.create(command("capacity-a", ImportDuplicatePolicy.COALESCE))) {
            assertThat(paused.capacity().state()).isEqualTo(ImportWorkspaceCapacity.State.PAUSED);
        }
        assertThatThrownBy(() -> paused.create(command("capacity-b", ImportDuplicatePolicy.COALESCE)))
                .isInstanceOfSatisfying(ImportWorkspaceException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportWorkspaceException.Reason.CAPACITY_PAUSED));
    }

    @Test
    void stagesOneHundredThousandRowsWithBoundedHeapState() {
        JdbcImportWorkspace workspace = workspace(limits(100_000));
        CreateImportWorkspaceCommand command = command("load", ImportDuplicatePolicy.COALESCE);

        long started = System.nanoTime();
        ImportStage stage;
        try (ImportWorkspaceWriter writer = workspace.create(command)) {
            for (int index = 1; index <= 100_000; index++) {
                writer.append(row(index, "key-" + index,
                        Map.of("ip", ImportCell.value("192.0.2." + (index % 255)))));
            }
            stage = writer.seal();
        }

        assertThat(stage.sourceRows()).isEqualTo(100_000);
        assertThat(stage.acceptedRows()).isEqualTo(100_000);
        assertThat(stage.rejectedRows()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(45));
    }

    private JdbcImportWorkspace workspace(ImportWorkspaceLimits limits) {
        return workspace(tempDir.resolve("workspace"), limits);
    }

    private JdbcImportWorkspace workspace(Path root, ImportWorkspaceLimits limits) {
        return new JdbcImportWorkspace(root, limits, CLOCK);
    }

    private ImportWorkspaceLimits limits(long rows) {
        return new ImportWorkspaceLimits(
                rows, 4, 32, Math.max(rows, 10),
                512L * 1024 * 1024,
                1024L * 1024 * 1024,
                900L * 1024 * 1024,
                800L * 1024 * 1024,
                1_000,
                DelimitedInputLimits.defaults());
    }

    private CreateImportWorkspaceCommand command(String delivery, ImportDuplicatePolicy duplicatePolicy) {
        return new CreateImportWorkspaceCommand(
                new ImportDeliveryId(delivery),
                new ImportSnapshot(
                        new ImportSnapshotReference("snapshot:" + delivery),
                        new ImportSha256("a".repeat(64)), 4096),
                new ImportContractPin(
                        new ImportContractId("ip-list-v1"), 1,
                        new ImportContractFingerprint("b".repeat(64))),
                duplicatePolicy);
    }

    private ImportLogicalRow row(long sourceRow, String key, Map<String, ImportCell> values) {
        return row(sourceRow, key, values, null);
    }

    private ImportLogicalRow row(long sourceRow,
                                 String key,
                                 Map<String, ImportCell> values,
                                 Integer requestedSlot) {
        LinkedHashMap<String, ImportCell> ordered = new LinkedHashMap<>(values);
        CanonicalKeyMaterial material = new CanonicalKeyMaterial(
                "ip-row-v1", hashFor(key), "[\"" + key + "\"]");
        ImportArtifactBranch branch = new ImportArtifactBranch(
                "ip_list", ImportArtifactRole.PRIMARY, ordered,
                requestedSlot == null ? OptionalLong.empty() : OptionalLong.of(requestedSlot),
                Optional.of(material), List.of(material));
        return new ImportLogicalRow(sourceRow, List.of(branch));
    }

    private String hashFor(String key) {
        return "%064x".formatted(Integer.toUnsignedLong(key.hashCode()));
    }

    private Path sealedFile() throws Exception {
        return sealedFile(tempDir.resolve("workspace"));
    }

    private Path sealedFile(Path root) throws Exception {
        try (var paths = Files.list(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".sealed.db"))
                    .findFirst().orElseThrow();
        }
    }

    private List<String> acceptedCells(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var resultSet = connection.createStatement().executeQuery("""
                     SELECT cell.target_column, cell.presence, cell.value
                     FROM stage_input_row input
                     JOIN stage_branch branch ON branch.source_row_number = input.source_row_number
                     JOIN stage_cell cell ON cell.branch_id = branch.branch_id
                     WHERE input.status = 'ACCEPTED'
                     ORDER BY cell.target_column
                     """)) {
            var cells = new java.util.ArrayList<String>();
            while (resultSet.next()) {
                cells.add(resultSet.getString(1) + "=" + resultSet.getInt(2) + ":" + resultSet.getString(3));
            }
            return List.copyOf(cells);
        }
    }

    private long queryLong(String sql) throws Exception {
        return queryLong(sealedFile(), sql);
    }

    private long queryLong(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var resultSet = connection.createStatement().executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : -1;
        }
    }

    private String queryString(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sealedFile());
             var resultSet = connection.createStatement().executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private record AdoptionEvidence(ImportSnapshot snapshot, ImportContractPin contract) {
    }
}
