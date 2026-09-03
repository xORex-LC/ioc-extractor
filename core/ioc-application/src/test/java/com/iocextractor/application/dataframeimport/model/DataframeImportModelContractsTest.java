package com.iocextractor.application.dataframeimport.model;

import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.in.dataframeimport.ReplayDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.ValidateDataframeImportResult;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DataframeImportModelContractsTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void workspaceLimitsRejectEveryInvalidBoundAndPreserveUsefulDefaults() {
        ImportWorkspaceLimits defaults = ImportWorkspaceLimits.defaults();
        assertThat(defaults.maximumSourceRows()).isPositive();
        assertThat(defaults.maximumWorkspaceBytes()).isGreaterThan(defaults.maximumStageBytes());
        assertThat(defaults.resumeAtBytes()).isLessThan(defaults.pauseAtBytes());
        assertThat(defaults.pauseAtBytes()).isLessThan(defaults.maximumWorkspaceBytes());

        assertInvalid(List.of(
                () -> limits(0, 1, 1, 1, 10, 20, 15, 5, 1, inputLimits()),
                () -> limits(1, 0, 1, 1, 10, 20, 15, 5, 1, inputLimits()),
                () -> limits(1, 1, 0, 1, 10, 20, 15, 5, 1, inputLimits()),
                () -> limits(1, 1, 1, 0, 10, 20, 15, 5, 1, inputLimits()),
                () -> limits(1, 1, 1, 1, 0, 20, 15, 5, 1, inputLimits()),
                () -> limits(1, 1, 1, 1, 10, 9, 8, 5, 1, inputLimits()),
                () -> limits(1, 1, 1, 1, 10, 20, 15, -1, 1, inputLimits()),
                () -> limits(1, 1, 1, 1, 10, 20, 5, 5, 1, inputLimits()),
                () -> limits(1, 1, 1, 1, 10, 20, 20, 5, 1, inputLimits()),
                () -> limits(1, 1, 1, 1, 10, 20, 15, 5, 0, inputLimits()),
                () -> limits(1, 1, 1, 1, 10, 20, 15, 5, 1, null)));
    }

    @Test
    void workspaceCapacityRejectsIncompleteOrImpossibleUsageEvidence() {
        assertThat(new ImportWorkspaceCapacity(
                0, 1, ImportWorkspaceCapacity.State.ACCEPTING).state())
                .isEqualTo(ImportWorkspaceCapacity.State.ACCEPTING);

        assertInvalid(List.of(
                () -> new ImportWorkspaceCapacity(-1, 1, ImportWorkspaceCapacity.State.ACCEPTING),
                () -> new ImportWorkspaceCapacity(0, 0, ImportWorkspaceCapacity.State.PAUSED),
                () -> new ImportWorkspaceCapacity(0, 1, null)));
    }

    @Test
    void rowIssuesRequireSafeStableEvidence() {
        assertThat(new ImportRowIssue(1, null, "IMPORT.ROW_INVALID"))
                .extracting(ImportRowIssue::sourceRowNumber, ImportRowIssue::code)
                .containsExactly(1L, "IMPORT.ROW_INVALID");

        assertInvalid(List.of(
                () -> new ImportRowIssue(0, "ip_list", "IMPORT.ROW_INVALID"),
                () -> new ImportRowIssue(1, "ip_list", " ")));
        assertThatNullPointerException()
                .isThrownBy(() -> new ImportRowIssue(1, "ip_list", null))
                .withMessage("code");
    }

    @Test
    void deliveryStatusRequiresACompleteNonNegativeHeadSnapshot() {
        ImportDeliveryStatus noHead = status(
                Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty());
        ImportDeliveryStatus head = status(
                Optional.of(new ImportDeliverySequence(1)),
                Optional.of(ImportDeliveryState.CLAIMING),
                Optional.of(Duration.ZERO),
                2,
                Optional.of(Duration.ofSeconds(3)),
                Optional.of("IMPORT.RETRY"));

        assertThat(noHead.stateCounts()).containsEntry(ImportDeliveryState.DETECTED, 1L);
        assertThat(head.headRetryCount()).isEqualTo(2);

        assertInvalid(List.of(
                () -> new ImportDeliveryStatus(
                        Map.of(ImportDeliveryState.DETECTED, -1L), Optional.empty(), Optional.empty(),
                        Optional.empty(), 0, Optional.empty(), Optional.empty(), false),
                () -> status(Optional.empty(), Optional.empty(), Optional.empty(), -1,
                        Optional.empty(), Optional.empty()),
                () -> status(Optional.of(new ImportDeliverySequence(1)),
                        Optional.of(ImportDeliveryState.CLAIMING), Optional.of(Duration.ofSeconds(-1)),
                        0, Optional.empty(), Optional.empty()),
                () -> status(Optional.of(new ImportDeliverySequence(1)),
                        Optional.of(ImportDeliveryState.CLAIMING), Optional.of(Duration.ZERO),
                        0, Optional.of(Duration.ofSeconds(-1)), Optional.empty()),
                () -> status(Optional.of(new ImportDeliverySequence(1)),
                        Optional.of(ImportDeliveryState.CLAIMING), Optional.of(Duration.ZERO),
                        0, Optional.empty(), Optional.of(" ")),
                () -> status(Optional.of(new ImportDeliverySequence(1)), Optional.empty(),
                        Optional.of(Duration.ZERO), 0, Optional.empty(), Optional.empty()),
                () -> status(Optional.of(new ImportDeliverySequence(1)),
                        Optional.of(ImportDeliveryState.CLAIMING), Optional.empty(),
                        0, Optional.empty(), Optional.empty()),
                () -> status(Optional.empty(), Optional.empty(), Optional.empty(),
                        1, Optional.empty(), Optional.empty()),
                () -> status(Optional.empty(), Optional.empty(), Optional.empty(),
                        0, Optional.of(Duration.ZERO), Optional.empty()),
                () -> status(Optional.empty(), Optional.empty(), Optional.empty(),
                        0, Optional.empty(), Optional.of("IMPORT.RETRY"))));
    }

    @Test
    void retentionTargetSupportsAgeOrCountAndRequiresAnArchiveDestination() {
        ImportTerminalRetentionTarget deleteByCount = retention(
                "successful", Duration.ZERO, 1, RetentionAction.DELETE, null);
        ImportTerminalRetentionTarget archiveByAge = retention(
                "unsuccessful", Duration.ofDays(1), 0, RetentionAction.ARCHIVE, Path.of("archive"));

        assertThat(deleteByCount.maxCount()).isEqualTo(1);
        assertThat(archiveByAge.archiveDirectory()).isEqualTo(Path.of("archive"));

        assertInvalid(List.of(
                () -> retention(" ", Duration.ofDays(1), 0, RetentionAction.DELETE, null),
                () -> new ImportTerminalRetentionTarget(
                        "empty", Set.of(), Duration.ofDays(1), 0, RetentionAction.DELETE, null),
                () -> retention("negative-count", Duration.ofDays(1), -1, RetentionAction.DELETE, null),
                () -> retention("disabled", null, 0, RetentionAction.DELETE, null),
                () -> retention("zero-age", Duration.ZERO, 0, RetentionAction.DELETE, null),
                () -> retention("negative-age", Duration.ofSeconds(-1), 1, RetentionAction.DELETE, null)));
        assertThatNullPointerException()
                .isThrownBy(() -> retention(
                        "archive", Duration.ofDays(1), 0, RetentionAction.ARCHIVE, null))
                .withMessage("archiveDirectory is required for ARCHIVE import retention target");
    }

    @Test
    void commitEvidenceDerivesAllTerminalOutcomesAndRejectsNegativeCounts() {
        assertThat(commitEvidence(1, 0, 1).terminalOutcome()).isEqualTo(ImportTerminalOutcome.SUCCEEDED);
        assertThat(commitEvidence(0, 1, 0).terminalOutcome()).isEqualTo(ImportTerminalOutcome.REJECTED);
        assertThat(commitEvidence(1, 1, 1).terminalOutcome())
                .isEqualTo(ImportTerminalOutcome.COMPLETED_WITH_ERRORS);

        assertInvalid(List.of(
                () -> commitEvidence(-1, 0, 0),
                () -> commitEvidence(0, -1, 0),
                () -> commitEvidence(0, 0, -1)));
    }

    @Test
    void canonicalImportResultRequiresExactPositiveProjectionGenerations() {
        CanonicalImportResult valid = canonicalResult(
                1, 0, 1, Set.of("ip_list"), Map.of("ip_list", 2L));
        assertThat(valid.projectionGenerations()).containsEntry("ip_list", 2L);

        assertInvalid(List.of(
                () -> canonicalResult(-1, 0, 0, Set.of(), Map.of()),
                () -> canonicalResult(0, -1, 0, Set.of(), Map.of()),
                () -> canonicalResult(0, 0, -1, Set.of(), Map.of()),
                () -> canonicalResult(0, 0, 0, Set.of("ip_list"), Map.of()),
                () -> canonicalResult(
                        0, 0, 0, Set.of("ip_list"), Map.of("ip_list", 0L))));
    }

    @Test
    void artifactBranchRequiresConsistentPoliciesSlotsAndUniqueMatchDefinitions() {
        Map<String, ImportCell> cells = Map.of("ip", ImportCell.value("192.0.2.1"));
        ImportArtifactBranch unresolved = new ImportArtifactBranch(
                "ip_list", ImportArtifactRole.PRIMARY, cells, OptionalLong.empty());
        ImportArtifactBranch resolved = new ImportArtifactBranch(
                "ip_list", ImportArtifactRole.PRIMARY, cells, OptionalLong.of(1),
                Optional.of(key("record", "one")), List.of(key("match", "one")));

        assertThat(unresolved.mergePolicies()).containsEntry("ip", ImportMergePolicy.AUTHORITATIVE);
        assertThat(resolved.recordKey()).contains(key("record", "one"));

        assertInvalid(List.of(
                () -> branch(" ", ImportArtifactRole.PRIMARY, cells,
                        Map.of("ip", ImportMergePolicy.AUTHORITATIVE), OptionalLong.empty(), List.of()),
                () -> branch("ip_list", ImportArtifactRole.PRIMARY, cells,
                        Map.of("ip", ImportMergePolicy.AUTHORITATIVE), OptionalLong.of(0), List.of()),
                () -> branch("hashes", ImportArtifactRole.RELATED, cells,
                        Map.of("ip", ImportMergePolicy.AUTHORITATIVE), OptionalLong.of(1), List.of()),
                () -> branch("ip_list", ImportArtifactRole.PRIMARY, cells,
                        Map.of("other", ImportMergePolicy.AUTHORITATIVE), OptionalLong.empty(), List.of()),
                () -> branch("ip_list", ImportArtifactRole.PRIMARY, cells,
                        Map.of("ip", ImportMergePolicy.AUTHORITATIVE), OptionalLong.empty(),
                        List.of(key("match", "one"), key("match", "two")))));
    }

    @Test
    void stageCountsCannotBeNegativeOrExceedTheirSourceRows() {
        assertThat(stage(3, 2, 1).sourceRows()).isEqualTo(3);
        assertInvalid(List.of(
                () -> stage(-1, 0, 0),
                () -> stage(1, -1, 0),
                () -> stage(1, 0, -1),
                () -> stage(1, 2, 0),
                () -> stage(2, 1, 2)));
    }

    @Test
    void deliveryAggregateEnforcesTerminalAndTimelineInvariants() {
        ImportDelivery forward = delivery(
                ImportDeliveryState.STAGED, Optional.empty(), Optional.empty(), NOW, NOW);
        ImportDelivery replay = delivery(
                ImportDeliveryState.TERMINAL,
                Optional.of(new ImportDeliveryId("original")),
                Optional.of(ImportTerminalOutcome.SUCCEEDED),
                NOW,
                NOW.plusSeconds(1));

        assertThat(forward.sourceOccurrenceKind()).isEqualTo(ImportSourceOccurrenceKind.FORWARD);
        assertThat(forward.snapshot()).contains(snapshot());
        assertThat(forward.contract()).contains(contract());
        assertThat(forward.stage()).contains(stage(1, 1, 0));
        assertThat(forward.attemptCount()).isZero();
        assertThat(replay.sourceOccurrenceKind())
                .isEqualTo(ImportSourceOccurrenceKind.SOURCE_DETACHED_REPLAY);

        assertInvalid(List.of(
                () -> delivery(" ", ImportDeliveryState.STAGED, 1, Optional.empty(),
                        Optional.empty(), NOW, NOW),
                () -> delivery("candidate", ImportDeliveryState.STAGED, -1, Optional.empty(),
                        Optional.empty(), NOW, NOW),
                () -> delivery("candidate", ImportDeliveryState.TERMINAL, 1, Optional.empty(),
                        Optional.empty(), NOW, NOW),
                () -> delivery("candidate", ImportDeliveryState.STAGED, 1, Optional.empty(),
                        Optional.of(ImportTerminalOutcome.SUCCEEDED), NOW, NOW),
                () -> delivery("candidate", ImportDeliveryState.STAGED, 1, Optional.empty(),
                        Optional.empty(), NOW, NOW.minusSeconds(1))));
    }

    @Test
    void evidenceAndCheckpointModelsPreserveForwardDependencyOrder() {
        assertThat(new ImportDeliveryEvidence(
                Optional.of(snapshot()), Optional.of(contract()), Optional.of(stage(1, 1, 0))).stage())
                .isPresent();
        assertThat(ImportDeliveryCheckpoint.none().snapshot()).isEmpty();
        assertThat(ImportDeliveryCheckpoint.snapshot(snapshot()).snapshot()).contains(snapshot());
        assertThat(ImportDeliveryCheckpoint.contract(contract()).contract()).contains(contract());
        assertThat(ImportDeliveryCheckpoint.stage(stage(1, 1, 0)).stage()).contains(stage(1, 1, 0));

        assertInvalid(List.of(
                () -> new ImportDeliveryEvidence(
                        Optional.empty(), Optional.of(contract()), Optional.empty()),
                () -> new ImportDeliveryEvidence(
                        Optional.of(snapshot()), Optional.empty(), Optional.of(stage(1, 1, 0))),
                () -> new ImportDeliveryCheckpoint(
                        Optional.of(snapshot()), Optional.of(contract()), Optional.empty())));
    }

    @Test
    void transitionsAndRetriesEnforceCasTerminalAndTimeContracts() {
        ImportDeliveryTransition terminal = new ImportDeliveryTransition(
                new ImportDeliveryId("delivery-1"), ImportDeliveryState.FINALIZING, 1,
                ImportDeliveryState.TERMINAL, Optional.of(ImportTerminalOutcome.SUCCEEDED), NOW);
        ImportRetrySchedule retry = retry(ImportDeliveryState.CLAIMING, 1, NOW.plusSeconds(1), "IMPORT.RETRY");
        assertThat(terminal.checkpoint()).isEqualTo(ImportDeliveryCheckpoint.none());
        assertThat(retry.failedAttempt()).isTrue();

        assertInvalid(List.of(
                () -> new ImportDeliveryTransition(
                        new ImportDeliveryId("delivery-1"), ImportDeliveryState.STAGED, -1,
                        ImportDeliveryState.PROMOTING, Optional.empty(), NOW),
                () -> new ImportDeliveryTransition(
                        new ImportDeliveryId("delivery-1"), ImportDeliveryState.FINALIZING, 1,
                        ImportDeliveryState.TERMINAL, Optional.empty(), NOW),
                () -> new ImportDeliveryTransition(
                        new ImportDeliveryId("delivery-1"), ImportDeliveryState.STAGED, 1,
                        ImportDeliveryState.PROMOTING, Optional.of(ImportTerminalOutcome.SUCCEEDED), NOW),
                () -> new ImportDeliveryTransition(
                        new ImportDeliveryId("delivery-1"), ImportDeliveryState.STAGED, 1,
                        ImportDeliveryState.PROMOTING, Optional.empty(), ImportDeliveryCheckpoint.none(),
                        Optional.of(" "), NOW),
                () -> retry(ImportDeliveryState.CLAIMING, -1, NOW, "IMPORT.RETRY"),
                () -> retry(ImportDeliveryState.CLAIMING, 1, NOW, " "),
                () -> retry(ImportDeliveryState.TERMINAL, 1, NOW, "IMPORT.RETRY"),
                () -> retry(ImportDeliveryState.CLAIMING, 1, NOW.minusSeconds(1), "IMPORT.RETRY")));
    }

    @Test
    void logicalAndRejectedRowsRequireOnePrimaryAndSameRowIssues() {
        ImportArtifactBranch primary = new ImportArtifactBranch(
                "ip_list", ImportArtifactRole.PRIMARY,
                Map.of("ip", ImportCell.value("192.0.2.1")), OptionalLong.empty());
        ImportArtifactBranch related = new ImportArtifactBranch(
                "hashes", ImportArtifactRole.RELATED,
                Map.of("hash", ImportCell.value("A".repeat(32))), OptionalLong.empty());
        assertThat(new ImportLogicalRow(1, List.of(primary, related)).branches()).hasSize(2);
        assertThat(new ImportRejectedLogicalRow(
                1, List.of(new ImportRowIssue(1, "ip_list", "IMPORT.INVALID"))).issues()).hasSize(1);

        assertInvalid(List.of(
                () -> new ImportLogicalRow(0, List.of(primary)),
                () -> new ImportLogicalRow(1, List.of(related)),
                () -> new ImportLogicalRow(1, List.of(primary, primary)),
                () -> new ImportRejectedLogicalRow(0,
                        List.of(new ImportRowIssue(1, "ip_list", "IMPORT.INVALID"))),
                () -> new ImportRejectedLogicalRow(1, List.of()),
                () -> new ImportRejectedLogicalRow(1,
                        List.of(new ImportRowIssue(2, "ip_list", "IMPORT.INVALID")))));
    }

    @Test
    void readinessFactoriesKeepFailuresValueFreeAndRetrySemanticsExplicit() {
        ImportSourceId sourceId = new ImportSourceId("source-1");
        assertThat(ImportSourceReadiness.ready(sourceId)).satisfies(readiness -> {
            assertThat(readiness.status()).isEqualTo(ImportSourceReadinessStatus.READY);
            assertThat(readiness.retryEligible()).isFalse();
        });
        assertThat(ImportSourceReadiness.capabilityFailed(
                sourceId, ImportSourceReadinessPhase.PRIVATE_OBJECT_FLOW, true).status())
                .isEqualTo(ImportSourceReadinessStatus.TRANSIENTLY_UNAVAILABLE);
        assertThat(ImportSourceReadiness.capabilityFailed(
                sourceId, ImportSourceReadinessPhase.PRIVATE_OBJECT_FLOW, false).status())
                .isEqualTo(ImportSourceReadinessStatus.INCOMPATIBLE);
        assertThat(ImportSourceReadiness.namespaceIncompatible(sourceId).phase())
                .isEqualTo(ImportSourceReadinessPhase.NAMESPACE);

        assertInvalid(List.of(
                () -> new ImportSourceReadiness(
                        sourceId, ImportSourceReadinessPhase.COMPLETE,
                        ImportSourceReadinessStatus.READY, " ", false),
                () -> new ImportSourceReadiness(
                        sourceId, ImportSourceReadinessPhase.COMPLETE,
                        ImportSourceReadinessStatus.READY, "IMPORT.SOURCE_READY", true)));
    }

    @Test
    void primitiveImportValuesNormalizeTokensAndRejectInvalidExternalData() {
        assertThat(new ImportSha256("A".repeat(64)).value()).isEqualTo(DIGEST);
        assertThat(new ImportContractFingerprint("B".repeat(64)).value()).isEqualTo("b".repeat(64));
        assertThat(new ImportCatalogFingerprint("C".repeat(64)).value()).isEqualTo("c".repeat(64));
        assertThat(ImportMergePolicy.parse(" Authoritative ")).isEqualTo(ImportMergePolicy.AUTHORITATIVE);
        assertThat(ImportMergePolicy.FILL_MISSING.isAllowedBy(ImportMergePolicy.AUTHORITATIVE)).isTrue();
        assertThat(ImportMergePolicy.AUTHORITATIVE.isAllowedBy(ImportMergePolicy.FILL_MISSING)).isFalse();
        assertThat(ImportMergePolicy.KEEP_EXISTING.isAllowedBy(null)).isFalse();
        assertThat(new ImportDeliverySequence(1).compareTo(new ImportDeliverySequence(2))).isNegative();

        assertInvalid(List.of(
                () -> new DelimitedInputLimits(0, 1, 1, 1),
                () -> new DelimitedInputLimits(1, 0, 1, 1),
                () -> new DelimitedInputLimits(1, 1, 0, 1),
                () -> new DelimitedInputLimits(1, 1, 2, 1),
                () -> new DelimitedDialect(';', ';', ImportRecordSeparator.LF, true, List.of()),
                () -> new DelimitedDialect(';', '"', ImportRecordSeparator.LF, false, List.of()),
                () -> new ImportDelimitedRecord(0, Map.of()),
                () -> new ImportDeliveryId(" "),
                () -> new ImportDeliverySequence(0),
                () -> new ImportSourceId(" "),
                () -> new ImportSnapshotReference(" "),
                () -> new ImportStageReference(" "),
                () -> new ImportContractId(" "),
                () -> new ImportRequestedSlotPolicy(" ", ImportExistingSlotPolicy.PRESERVE_EXISTING),
                () -> new ImportSha256("invalid"),
                () -> new ImportContractFingerprint("invalid"),
                () -> new ImportCatalogFingerprint("invalid"),
                () -> new ImportSnapshot(new ImportSnapshotReference("snapshot:test"),
                        new ImportSha256(DIGEST), -1),
                () -> new ImportContractPin(
                        new ImportContractId("contract"), 0, new ImportContractFingerprint(DIGEST)),
                () -> ImportMergePolicy.parse(null),
                () -> ImportMergePolicy.parse("unknown")));
    }

    @Test
    void validationAndRecoveryResultsRejectImpossibleOperatorCounters() {
        ValidateDataframeImportResult validation = new ValidateDataframeImportResult(
                true, Optional.of(new ImportContractFingerprint(DIGEST)), 3, 2, 1,
                List.of("IMPORT.ROW_REJECTED"));
        assertThat(validation.diagnosticCodes()).containsExactly("IMPORT.ROW_REJECTED");

        assertInvalid(List.of(
                () -> new ValidateDataframeImportResult(
                        false, Optional.empty(), -1, 0, 0, List.of()),
                () -> new ValidateDataframeImportResult(
                        false, Optional.empty(), 0, -1, 0, List.of()),
                () -> new ValidateDataframeImportResult(
                        false, Optional.empty(), 0, 0, -1, List.of()),
                () -> new RecoverDataframeImportsResult(-1, 0, 0),
                () -> new RecoverDataframeImportsResult(0, -1, 0),
                () -> new RecoverDataframeImportsResult(0, 0, -1),
                () -> new RecoverDataframeImportsResult(1, 2, 0)));
        assertThatNullPointerException().isThrownBy(() -> new ValidateDataframeImportResult(
                false, null, 0, 0, 0, List.of())).withMessage("contractFingerprint");
        assertThatNullPointerException().isThrownBy(() -> new ValidateDataframeImportResult(
                false, Optional.empty(), 0, 0, 0, null)).withMessage("diagnosticCodes");
    }

    @Test
    void importReportRequestSnapshotsOnlySafeCompleteEvidence() {
        PublishImportReportCommand command = reportCommand(1, 1, 1, List.of("IMPORT.WARNING"));
        assertThat(command.affectedArtifacts()).containsExactly("ip_list");
        assertThat(command.deliveryCodes()).containsExactly("IMPORT.WARNING");

        assertInvalid(List.of(
                () -> reportCommand(-1, 0, 0, List.of()),
                () -> reportCommand(0, -1, 0, List.of()),
                () -> reportCommand(0, 0, -1, List.of()),
                () -> reportCommand(0, 0, 0, List.of(" "))));
        assertThatNullPointerException().isThrownBy(() -> reportCommand(
                0, 0, 0, java.util.Arrays.asList("IMPORT.WARNING", null)));
    }

    @Test
    void replayCommandRequiresDistinctTerminalAndNewDeliveryIdentities() {
        ImportDeliveryId terminal = new ImportDeliveryId("terminal-1");
        ImportDeliveryId replay = new ImportDeliveryId("replay-1");
        assertThat(new ReplayDataframeImportCommand(terminal, replay).newDeliveryId())
                .isEqualTo(replay);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayDataframeImportCommand(terminal, terminal))
                .withMessage("Replay must create a new import delivery identity");
    }

    private ImportWorkspaceLimits limits(
            long maximumSourceRows,
            int maximumBranchesPerRow,
            int maximumCellsPerBranch,
            long maximumRowErrors,
            long maximumStageBytes,
            long maximumWorkspaceBytes,
            long pauseAtBytes,
            long resumeAtBytes,
            int transactionBatchRows,
            DelimitedInputLimits inputLimits) {
        return new ImportWorkspaceLimits(
                maximumSourceRows, maximumBranchesPerRow, maximumCellsPerBranch,
                maximumRowErrors, maximumStageBytes, maximumWorkspaceBytes,
                pauseAtBytes, resumeAtBytes, transactionBatchRows, inputLimits);
    }

    private PublishImportReportCommand reportCommand(
            long accepted,
            long rejected,
            long mutations,
            List<String> codes) {
        return new PublishImportReportCommand(
                new ImportDeliveryId("delivery-1"), new ImportSourceId("source-1"),
                snapshot().reference(), Optional.of(contract()), ImportTerminalOutcome.SUCCEEDED,
                accepted, rejected, mutations, Set.of("ip_list"), codes, List.of());
    }

    private DelimitedInputLimits inputLimits() {
        return new DelimitedInputLimits(10, 10, 10, 100);
    }

    private ImportDeliveryStatus status(
            Optional<ImportDeliverySequence> headSequence,
            Optional<ImportDeliveryState> headState,
            Optional<Duration> headAge,
            int retryCount,
            Optional<Duration> retryDelay,
            Optional<String> code) {
        return new ImportDeliveryStatus(
                Map.of(ImportDeliveryState.DETECTED, 1L), headSequence, headState, headAge,
                retryCount, retryDelay, code, true);
    }

    private ImportTerminalRetentionTarget retention(
            String name,
            Duration maxAge,
            int maxCount,
            RetentionAction action,
            Path archiveDirectory) {
        return new ImportTerminalRetentionTarget(
                name, Set.of(ImportTerminalOutcome.SUCCEEDED), maxAge, maxCount, action, archiveDirectory);
    }

    private ImportCommitEvidence commitEvidence(long accepted, long rejected, long mutations) {
        return new ImportCommitEvidence(
                new ImportDeliveryId("delivery-1"), accepted, rejected, mutations,
                Set.of("ip_list"), List.of());
    }

    private CanonicalImportResult canonicalResult(
            long accepted,
            long rejected,
            long mutations,
            Set<String> affectedArtifacts,
            Map<String, Long> projectionGenerations) {
        return new CanonicalImportResult(
                ImportPromotionOutcome.COMMITTED, accepted, rejected, mutations,
                affectedArtifacts, affectedArtifacts, projectionGenerations, NOW);
    }

    private ImportArtifactBranch branch(
            String artifact,
            ImportArtifactRole role,
            Map<String, ImportCell> cells,
            Map<String, ImportMergePolicy> policies,
            OptionalLong slot,
            List<CanonicalKeyMaterial> matchKeys) {
        return new ImportArtifactBranch(
                artifact, role, cells, policies, slot, Optional.empty(), matchKeys);
    }

    private CanonicalKeyMaterial key(String definition, String canonical) {
        return new CanonicalKeyMaterial(definition, DIGEST, canonical);
    }

    private ImportDelivery delivery(
            ImportDeliveryState state,
            Optional<ImportDeliveryId> replayOf,
            Optional<ImportTerminalOutcome> terminalOutcome,
            Instant createdAt,
            Instant updatedAt) {
        return delivery("candidate", state, 1, replayOf, terminalOutcome, createdAt, updatedAt);
    }

    private ImportDelivery delivery(
            String candidateToken,
            ImportDeliveryState state,
            long version,
            Optional<ImportDeliveryId> replayOf,
            Optional<ImportTerminalOutcome> terminalOutcome,
            Instant createdAt,
            Instant updatedAt) {
        return new ImportDelivery(
                new ImportDeliveryId("delivery-1"), new ImportDeliverySequence(1),
                new ImportSourceId("source-1"), candidateToken, replayOf, state, version,
                new ImportDeliveryEvidence(
                        Optional.of(snapshot()), Optional.of(contract()), Optional.of(stage(1, 1, 0))),
                new ImportDeliveryRetryState(0, Optional.empty(), Optional.empty()),
                terminalOutcome, createdAt, updatedAt);
    }

    private ImportSnapshot snapshot() {
        return new ImportSnapshot(
                new ImportSnapshotReference("snapshot:test"), new ImportSha256(DIGEST), 10);
    }

    private ImportContractPin contract() {
        return new ImportContractPin(
                new ImportContractId("contract-v1"), 1, new ImportContractFingerprint(DIGEST));
    }

    private ImportStage stage(long sourceRows, long acceptedRows, long rejectedRows) {
        return new ImportStage(
                new ImportStageReference("stage:test"), new ImportSha256(DIGEST),
                sourceRows, acceptedRows, rejectedRows);
    }

    private ImportRetrySchedule retry(
            ImportDeliveryState state,
            long version,
            Instant nextAttemptAt,
            String code) {
        return new ImportRetrySchedule(
                new ImportDeliveryId("delivery-1"), state, version,
                nextAttemptAt, code, true, NOW);
    }

    private void assertInvalid(List<ThrowingCallable> scenarios) {
        for (int index = 0; index < scenarios.size(); index++) {
            assertThatIllegalArgumentException()
                    .as("invalid scenario %s", index)
                    .isThrownBy(scenarios.get(index));
        }
    }
}
