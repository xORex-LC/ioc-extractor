package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.DelimitedInputReadException;
import com.iocextractor.application.dataframeimport.contract.ImportRecognitionException;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportCommitEvidence;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryRetryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceCapacity;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportReportStore;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportProcessingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DIGEST = "a".repeat(64);

    @Test
    void stagesSnapshotAndPersistsContractBeforeSealedWorkspaceEvidence() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        DataframeImportProcessingService service = service(
                ledger, command -> stagingResult(), this::idle);

        ProcessNextDataframeImportResult result = service.processNext();

        assertThat(result.workPerformed()).isTrue();
        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.STAGED);
        assertThat(ledger.current.contract()).contains(contract());
        assertThat(ledger.current.stage()).contains(stage());
        assertThat(ledger.transitionStates).containsExactly(
                ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryState.STAGING,
                ImportDeliveryState.STAGED);
    }

    @Test
    void adoptsSealedWorkspaceWhenRestartBeginsWithPinnedContract() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.CONTRACT_PINNED));
        ImportWorkspace workspace = workspace(Optional.of(stage()));
        DataframeImportProcessingService service = service(
                ledger, unusedStager(), this::idle, workspace,
                commitEvidenceStore(ledger.current.id()), command -> { }, unusedSourceLifecycle());

        service.processNext();

        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.STAGED);
        assertThat(ledger.current.stage()).contains(stage());
        assertThat(ledger.transitionStates).containsExactly(
                ImportDeliveryState.STAGING, ImportDeliveryState.STAGED);
    }

    @Test
    void restagesWhenNoCompatibleSealedWorkspaceCanBeAdopted() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.STAGING));
        AtomicBoolean stagingCalled = new AtomicBoolean();
        DataframeImportStager stager = command -> {
            stagingCalled.set(true);
            return stagingResult();
        };
        DataframeImportProcessingService service = service(
                ledger, stager, this::idle, workspace(Optional.empty()),
                commitEvidenceStore(ledger.current.id()), command -> { }, unusedSourceLifecycle());

        service.processNext();

        assertThat(stagingCalled).isTrue();
        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.STAGED);
        assertThat(ledger.transitionStates).containsExactly(ImportDeliveryState.STAGED);
    }

    @Test
    void failsClosedWhenRestartedStagingCannotReproduceThePinnedContract() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.STAGING));
        ImportContractPin differentContract = new ImportContractPin(
                new ImportContractId("different-v1"), 1, new ImportContractFingerprint(DIGEST));
        DataframeImportStager stager = command -> new ImportStagingResult(differentContract, stage());
        DataframeImportProcessingService service = service(
                ledger, stager, this::idle, workspace(Optional.empty()),
                commitEvidenceStore(ledger.current.id()), command -> { }, unusedSourceLifecycle());

        assertThatThrownBy(service::processNext)
                .isInstanceOf(DataframeImportConsistencyException.class)
                .hasMessage("Pinned import contract is unavailable after restart");
    }

    @Test
    void mapsEveryRecognitionFailureToItsStableTerminalCode() {
        var expectedCodes = java.util.Map.of(
                ImportRecognitionException.Reason.SOURCE_NOT_CONFIGURED,
                "IMPORT.SOURCE_NOT_CONFIGURED",
                ImportRecognitionException.Reason.CONTRACT_AMBIGUOUS,
                "IMPORT.CONTRACT_AMBIGUOUS");

        expectedCodes.forEach((reason, code) -> {
            StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
            AtomicReference<PublishImportReportCommand> published = new AtomicReference<>();
            DataframeImportStager stager = command -> {
                throw new ImportRecognitionException(reason, "safe recognition failure");
            };
            DataframeImportProcessingService service = service(
                    ledger, stager, this::idle, published::set);

            service.processNext();

            assertThat(ledger.current.terminalOutcome()).contains(ImportTerminalOutcome.REJECTED);
            assertThat(published.get().deliveryCodes()).containsExactly(code);
        });
    }

    @Test
    void distinguishesCapacityDeferralHardLimitRejectionAndWorkspaceContradiction() {
        StatefulLedger capacityLedger = workspaceFailure(ImportWorkspaceException.Reason.CAPACITY_PAUSED);
        assertThat(capacityLedger.current.state()).isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(capacityLedger.lastRetry.safeCode()).isEqualTo("IMPORT.CAPACITY_PAUSED");
        assertThat(capacityLedger.lastRetry.failedAttempt()).isFalse();

        StatefulLedger limitLedger = workspaceFailure(ImportWorkspaceException.Reason.HARD_LIMIT_EXCEEDED);
        assertThat(limitLedger.current.terminalOutcome()).contains(ImportTerminalOutcome.REJECTED);
        assertThat(limitLedger.lastReport.deliveryCodes()).containsExactly("IMPORT.LIMIT_EXCEEDED");

        for (ImportWorkspaceException.Reason reason : List.of(
                ImportWorkspaceException.Reason.INCOMPATIBLE_EXISTING_STAGE,
                ImportWorkspaceException.Reason.STORAGE_FAILURE)) {
            StatefulLedger ledger = workspaceFailure(reason);
            assertThat(ledger.lastRetry.safeCode()).isEqualTo("IMPORT.PROCESSING_FAILED");
            assertThat(ledger.lastRetry.failedAttempt()).isTrue();
        }

        for (ImportWorkspaceException.Reason reason : List.of(
                ImportWorkspaceException.Reason.STAGE_NOT_SEALED,
                ImportWorkspaceException.Reason.STAGE_INTEGRITY_FAILED)) {
            StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
            DataframeImportProcessingService service = service(
                    ledger, failingStager(reason), this::idle);
            assertThatThrownBy(service::processNext)
                    .isInstanceOf(DataframeImportConsistencyException.class)
                    .hasMessage("Import sealed-stage evidence is contradictory");
        }
    }

    @Test
    void defersUnexpectedStagingAndStablePromotionFailures() {
        StatefulLedger stagingLedger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        DataframeImportProcessingService stagingService = service(
                stagingLedger, command -> {
                    throw new IllegalStateException("stager unavailable");
                }, this::idle);

        stagingService.processNext();

        assertThat(stagingLedger.lastRetry.safeCode()).isEqualTo("IMPORT.PROCESSING_FAILED");
        assertThat(stagingLedger.lastRetry.failedAttempt()).isTrue();

        StatefulLedger promotionLedger = new StatefulLedger(delivery(ImportDeliveryState.STAGED));
        DataframeImportProcessingService promotionService = service(
                promotionLedger, unusedStager(), () -> {
                    throw new IllegalStateException("promotion unavailable");
                });

        promotionService.processNext();

        assertThat(promotionLedger.lastRetry.safeCode()).isEqualTo("IMPORT.PROCESSING_FAILED");
        assertThat(promotionLedger.current.state()).isEqualTo(ImportDeliveryState.STAGED);
    }

    @Test
    void preservesPromotionResultAndTurnsNoWorkIntoIdle() {
        StatefulLedger performedLedger = new StatefulLedger(delivery(ImportDeliveryState.STAGED));
        ProcessNextDataframeImportResult promoted = new ProcessNextDataframeImportResult(
                true, Optional.of(new ImportDeliveryId("promoted")));
        DataframeImportProcessingService performedService = service(
                performedLedger, unusedStager(), () -> promoted);

        assertThat(performedService.processNext()).isEqualTo(promoted);

        StatefulLedger idleLedger = new StatefulLedger(delivery(ImportDeliveryState.PROMOTING));
        DataframeImportProcessingService idleService = service(
                idleLedger, unusedStager(), this::idle);
        assertThat(idleService.processNext()).isEqualTo(idle());
    }

    @Test
    void retriesPostCommitFinalizationButRejectsMissingReceiptEvidence() {
        StatefulLedger retryLedger = new StatefulLedger(delivery(ImportDeliveryState.CANONICAL_COMMITTED));
        ImportReportStore failingReports = command -> {
            throw new IllegalStateException("report unavailable");
        };
        DataframeImportProcessingService retryService = service(
                retryLedger, unusedStager(), this::idle, unusedWorkspace(),
                commitEvidenceStore(retryLedger.current.id()), failingReports,
                unusedSourceLifecycle());

        retryService.processNext();

        assertThat(retryLedger.current.state()).isEqualTo(ImportDeliveryState.FINALIZING);
        assertThat(retryLedger.lastRetry.safeCode()).isEqualTo("IMPORT.FINALIZATION_FAILED");

        StatefulLedger missingReceiptLedger = new StatefulLedger(
                delivery(ImportDeliveryState.CANONICAL_COMMITTED));
        DataframeImportProcessingService missingReceiptService = service(
                missingReceiptLedger, unusedStager(), this::idle, unusedWorkspace(),
                emptyCommitEvidenceStore(), command -> { }, unusedSourceLifecycle());

        assertThatThrownBy(missingReceiptService::processNext)
                .isInstanceOf(DataframeImportConsistencyException.class)
                .hasMessage("Canonical import service state has no dataframe receipt");
    }

    @Test
    void returnsIdleForNoDueHeadAndForAdmissionOwnedHeadStates() {
        StatefulLedger noHead = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        noHead.dueHeadAvailable = false;
        assertThat(service(noHead, unusedStager(), this::idle).processNext()).isEqualTo(idle());

        StatefulLedger detected = new StatefulLedger(delivery(ImportDeliveryState.DETECTED));
        assertThat(service(detected, unusedStager(), this::idle).processNext()).isEqualTo(idle());
    }

    @Test
    void rejectsNegativeRetryDelayAndDurableRetryConflict() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        assertThatIllegalArgumentException().isThrownBy(() -> new DataframeImportProcessingService(
                ledger, unusedStager(), this::idle, unusedWorkspace(),
                commitEvidenceStore(ledger.current.id()), command -> { }, unusedSourceLifecycle(),
                CLOCK, Duration.ofNanos(-1)))
                .withMessage("Import processing retry delay must not be negative");

        ledger.retryResult = ImportLedgerTransitionResult.CONFLICT;
        DataframeImportProcessingService service = service(
                ledger, command -> {
                    throw new IllegalStateException("stager unavailable");
                }, this::idle);
        assertThatThrownBy(service::processNext)
                .isInstanceOf(DataframeImportConsistencyException.class)
                .hasMessage("Import retry scheduling conflicts with durable state");
    }

    @Test
    void finalizesAnAlreadyFinalizingDeliveryWithoutRepeatingTheTransition() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.FINALIZING));
        DataframeImportProcessingService service = service(
                ledger, unusedStager(), this::idle);

        service.processNext();

        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.TERMINAL);
        assertThat(ledger.transitionStates).containsExactly(ImportDeliveryState.TERMINAL);
    }

    @Test
    void defersRejectionFinalizationWhenTheReportCannotBePublished() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        DataframeImportStager rejectingStager = command -> {
            throw new ImportRecognitionException(
                    ImportRecognitionException.Reason.CONTRACT_NOT_RECOGNIZED,
                    "contract not recognized");
        };
        DataframeImportProcessingService service = service(
                ledger, rejectingStager, this::idle, command -> {
                    throw new IllegalStateException("report unavailable");
                });

        service.processNext();

        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(ledger.lastRetry.safeCode()).isEqualTo("IMPORT.FINALIZATION_FAILED");
    }

    @Test
    void rejectsPromotionFailureAfterAnImpossibleDurableStateChange() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.STAGED));
        DataframeImportProcessingService service = service(
                ledger, unusedStager(), () -> {
                    ledger.advance(ImportDeliveryState.CLAIMED, Optional.empty());
                    throw new IllegalStateException("response lost after invalid change");
                });

        assertThatThrownBy(service::processNext)
                .isInstanceOf(DataframeImportConsistencyException.class)
                .hasMessage("Import promotion failed after an invalid durable state change")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsIdempotentTransitionReplayButFailsClosedOnCasConflict() {
        StatefulLedger replayed = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        replayed.transitionResult = ImportLedgerTransitionResult.ALREADY_APPLIED;
        service(replayed, command -> stagingResult(), this::idle).processNext();
        assertThat(replayed.current.state()).isEqualTo(ImportDeliveryState.STAGED);

        StatefulLedger conflicting = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        conflicting.transitionResult = ImportLedgerTransitionResult.CONFLICT;
        assertThatThrownBy(() -> service(
                conflicting, command -> stagingResult(), this::idle).processNext())
                .isInstanceOf(DataframeImportConsistencyException.class)
                .hasMessage("Import processing transition conflicts with durable state");
    }

    @Test
    void rejectsDurableStagingStatesWithoutTheirRequiredEvidence() {
        ImportDelivery incomplete = new ImportDelivery(
                new ImportDeliveryId("delivery-1"), new ImportDeliverySequence(1),
                new ImportSourceId("source-1"), "candidate-1", Optional.empty(),
                ImportDeliveryState.CONTRACT_PINNED, 1,
                new ImportDeliveryEvidence(Optional.of(snapshot()), Optional.empty(), Optional.empty()),
                new ImportDeliveryRetryState(0, Optional.empty(), Optional.empty()),
                Optional.empty(), NOW, NOW);
        StatefulLedger ledger = new StatefulLedger(incomplete);

        assertThatThrownBy(() -> service(
                ledger, unusedStager(), this::idle, workspace(Optional.empty()),
                commitEvidenceStore(ledger.current.id()), command -> { },
                unusedSourceLifecycle()).processNext())
                .isInstanceOf(DataframeImportConsistencyException.class)
                .hasMessage("Import staging state has no pinned contract");
    }

    @Test
    void acceptsCanonicalCommitWhenPromotionResponseIsLost() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.STAGED));
        RuntimeException responseLost = new IllegalStateException("response lost");
        ProcessNextDataframeImportUseCase promotion = () -> {
            ledger.advance(ImportDeliveryState.CANONICAL_COMMITTED, Optional.empty());
            throw responseLost;
        };
        DataframeImportProcessingService service = service(ledger, unusedStager(), promotion);

        ProcessNextDataframeImportResult result = service.processNext();

        assertThat(result.workPerformed()).isTrue();
        assertThat(result.deliveryId()).contains(ledger.current.id());
        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
        assertThat(ledger.retrySchedules).isZero();
    }

    @Test
    void acceptsTerminalFinalizationWhenTransitionResponseIsLost() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.CANONICAL_COMMITTED));
        ledger.loseTerminalTransitionResponse = true;
        DataframeImportProcessingService service = service(
                ledger, unusedStager(), this::idle);

        ProcessNextDataframeImportResult result = service.processNext();

        assertThat(result.workPerformed()).isTrue();
        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.TERMINAL);
        assertThat(ledger.current.terminalOutcome()).contains(ImportTerminalOutcome.SUCCEEDED);
        assertThat(ledger.retrySchedules).isZero();
    }

    @Test
    void replayFinalizationDoesNotCallSourceDisposition() {
        ImportDelivery forward = delivery(ImportDeliveryState.CANONICAL_COMMITTED);
        ImportDelivery replay = new ImportDelivery(
                forward.id(), forward.sequence(), forward.sourceId(), forward.candidateToken(),
                Optional.of(new ImportDeliveryId("parent")), forward.state(), forward.version(),
                forward.evidence(), forward.retry(), forward.terminalOutcome(),
                forward.createdAt(), forward.updatedAt());
        StatefulLedger ledger = new StatefulLedger(replay);
        AtomicBoolean dispositionCalled = new AtomicBoolean();
        ManagedImportSourceLifecycle sources = new ManagedImportSourceLifecycle() {
            @Override
            public List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
                throw new AssertionError("source detection must not be called");
            }

            @Override
            public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
                throw new AssertionError("source claim must not be called");
            }

            @Override
            public void disposition(DispositionImportSourceCommand command) {
                dispositionCalled.set(true);
            }
        };
        DataframeImportProcessingService service = new DataframeImportProcessingService(
                ledger, unusedStager(), this::idle, unusedWorkspace(),
                commitEvidenceStore(replay.id()), command -> { }, sources,
                CLOCK, Duration.ofSeconds(5));

        service.processNext();

        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.TERMINAL);
        assertThat(dispositionCalled).isFalse();
    }

    @Test
    void acceptsTerminalRejectionWhenTransitionResponseIsLost() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        ledger.loseTerminalTransitionResponse = true;
        DataframeImportStager rejectingStager = command -> {
            throw new ImportRecognitionException(
                    ImportRecognitionException.Reason.CONTRACT_NOT_RECOGNIZED,
                    "contract not recognized");
        };
        DataframeImportProcessingService service = service(
                ledger, rejectingStager, this::idle);

        ProcessNextDataframeImportResult result = service.processNext();

        assertThat(result.workPerformed()).isTrue();
        assertThat(ledger.current.state()).isEqualTo(ImportDeliveryState.TERMINAL);
        assertThat(ledger.current.terminalOutcome()).contains(ImportTerminalOutcome.REJECTED);
        assertThat(ledger.retrySchedules).isZero();
    }

    @Test
    void preserves_safe_parser_reason_in_terminal_report() {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        DataframeImportStager rejectingStager = command -> {
            throw new DelimitedInputReadException(
                    DelimitedInputReadException.Reason.COLUMN_COUNT_MISMATCH,
                    "safe structural failure");
        };
        var published = new AtomicReference<PublishImportReportCommand>();
        DataframeImportProcessingService service = service(
                ledger, rejectingStager, this::idle, published::set);

        ProcessNextDataframeImportResult result = service.processNext();

        assertThat(result.workPerformed()).isTrue();
        assertThat(published.get()).isNotNull();
        assertThat(published.get().deliveryCodes()).containsExactly(
                "IMPORT.INPUT_INVALID", "IMPORT.INPUT_INVALID.COLUMN_COUNT_MISMATCH");
        assertThat(ledger.current.terminalOutcome()).contains(ImportTerminalOutcome.REJECTED);
    }

    private DataframeImportProcessingService service(
            StatefulLedger ledger,
            DataframeImportStager stager,
            ProcessNextDataframeImportUseCase promotion) {
        return service(ledger, stager, promotion, command -> { });
    }

    private DataframeImportProcessingService service(
            StatefulLedger ledger,
            DataframeImportStager stager,
            ProcessNextDataframeImportUseCase promotion,
            ImportReportStore reports) {
        return service(
                ledger, stager, promotion, unusedWorkspace(),
                commitEvidenceStore(ledger.current.id()), reports, unusedSourceLifecycle());
    }

    private DataframeImportProcessingService service(
            StatefulLedger ledger,
            DataframeImportStager stager,
            ProcessNextDataframeImportUseCase promotion,
            ImportWorkspace workspace,
            ImportCommitEvidenceStore commits,
            ImportReportStore reports,
            ManagedImportSourceLifecycle sources) {
        return new DataframeImportProcessingService(
                ledger,
                stager,
                promotion,
                workspace,
                commits,
                reports,
                sources,
                CLOCK,
                Duration.ofSeconds(5));
    }

    private StatefulLedger workspaceFailure(ImportWorkspaceException.Reason reason) {
        StatefulLedger ledger = new StatefulLedger(delivery(ImportDeliveryState.SNAPSHOT_PINNED));
        DataframeImportProcessingService service = service(
                ledger, failingStager(reason), this::idle, unusedWorkspace(),
                commitEvidenceStore(ledger.current.id()), command -> ledger.lastReport = command,
                unusedSourceLifecycle());
        service.processNext();
        return ledger;
    }

    private DataframeImportStager failingStager(ImportWorkspaceException.Reason reason) {
        return command -> {
            throw new ImportWorkspaceException(reason, "safe workspace failure");
        };
    }

    private ImportStagingResult stagingResult() {
        return new ImportStagingResult(contract(), stage());
    }

    private ImportContractPin contract() {
        return new ImportContractPin(
                new ImportContractId("ip-list-v1"), 1, new ImportContractFingerprint(DIGEST));
    }

    private ImportStage stage() {
        return new ImportStage(
                new ImportStageReference("stage:test"), new ImportSha256(DIGEST), 1, 1, 0);
    }

    private ImportSnapshot snapshot() {
        return new ImportSnapshot(
                new ImportSnapshotReference("snapshot:test"), new ImportSha256(DIGEST), 10);
    }

    private ImportWorkspace workspace(Optional<ImportStage> adopted) {
        return new ImportWorkspace() {
            @Override
            public ImportWorkspaceWriter create(CreateImportWorkspaceCommand command) {
                throw new AssertionError("workspace create must not be called");
            }

            @Override
            public ImportWorkspaceWriter rebuild(CreateImportWorkspaceCommand command) {
                throw new AssertionError("workspace rebuild must not be called");
            }

            @Override
            public ImportStage verifySealed(CreateImportWorkspaceCommand command, ImportStage expected) {
                throw new AssertionError("workspace verification must not be called");
            }

            @Override
            public Optional<ImportStage> adoptSealed(
                    ImportDeliveryId deliveryId,
                    ImportSnapshot snapshot,
                    ImportContractPin contract) {
                return adopted;
            }

            @Override
            public ImportWorkspaceCapacity capacity() {
                throw new AssertionError("workspace capacity must not be called");
            }

            @Override
            public void discard(ImportDeliveryId deliveryId) {
                throw new AssertionError("workspace discard must not be called");
            }
        };
    }

    private DataframeImportStager unusedStager() {
        return command -> {
            throw new AssertionError("staging must not be called");
        };
    }

    private ProcessNextDataframeImportResult idle() {
        return new ProcessNextDataframeImportResult(false, Optional.empty());
    }

    private ImportCommitEvidenceStore commitEvidenceStore(ImportDeliveryId deliveryId) {
        ImportCommitEvidence evidence = new ImportCommitEvidence(
                deliveryId, 1, 0, 1, Set.of("ip_list"), List.of());
        return new ImportCommitEvidenceStore() {
            @Override
            public Optional<ImportCommitEvidence> find(ImportDeliveryId requestedId) {
                return requestedId.equals(deliveryId) ? Optional.of(evidence) : Optional.empty();
            }

            @Override
            public void purge(ImportDeliveryId requestedId) {
                throw new AssertionError("commit evidence purge must not be called");
            }
        };
    }

    private ImportCommitEvidenceStore emptyCommitEvidenceStore() {
        return new ImportCommitEvidenceStore() {
            @Override
            public Optional<ImportCommitEvidence> find(ImportDeliveryId deliveryId) {
                return Optional.empty();
            }

            @Override
            public void purge(ImportDeliveryId deliveryId) {
                throw new AssertionError("commit evidence purge must not be called");
            }
        };
    }

    private ImportWorkspace unusedWorkspace() {
        return new ImportWorkspace() {
            @Override
            public ImportWorkspaceWriter create(CreateImportWorkspaceCommand command) {
                throw new AssertionError("workspace create must not be called");
            }

            @Override
            public ImportWorkspaceWriter rebuild(CreateImportWorkspaceCommand command) {
                throw new AssertionError("workspace rebuild must not be called");
            }

            @Override
            public ImportStage verifySealed(CreateImportWorkspaceCommand command, ImportStage expected) {
                throw new AssertionError("workspace verification must not be called");
            }

            @Override
            public Optional<ImportStage> adoptSealed(
                    ImportDeliveryId deliveryId,
                    ImportSnapshot snapshot,
                    ImportContractPin contract) {
                throw new AssertionError("workspace adoption must not be called");
            }

            @Override
            public ImportWorkspaceCapacity capacity() {
                throw new AssertionError("workspace capacity must not be called");
            }

            @Override
            public void discard(ImportDeliveryId deliveryId) {
                throw new AssertionError("workspace discard must not be called");
            }
        };
    }

    private ManagedImportSourceLifecycle unusedSourceLifecycle() {
        return new ManagedImportSourceLifecycle() {
            @Override
            public List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
                throw new AssertionError("source detection must not be called");
            }

            @Override
            public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
                throw new AssertionError("source claim must not be called");
            }

            @Override
            public void disposition(DispositionImportSourceCommand command) {
                // Terminal disposition is an expected part of finalization.
            }

        };
    }

    private ImportDelivery delivery(ImportDeliveryState state) {
        ImportSnapshot snapshot = new ImportSnapshot(
                new ImportSnapshotReference("snapshot:test"), new ImportSha256(DIGEST), 10);
        boolean hasSnapshot = state.ordinal() >= ImportDeliveryState.SNAPSHOT_PINNED.ordinal();
        boolean hasContract = state.ordinal() >= ImportDeliveryState.CONTRACT_PINNED.ordinal();
        boolean hasStage = state.ordinal() >= ImportDeliveryState.STAGED.ordinal();
        ImportDeliveryEvidence evidence = new ImportDeliveryEvidence(
                hasSnapshot ? Optional.of(snapshot) : Optional.empty(),
                hasContract ? Optional.of(contract()) : Optional.empty(),
                hasStage ? Optional.of(stage()) : Optional.empty());
        Optional<ImportTerminalOutcome> terminalOutcome = state == ImportDeliveryState.TERMINAL
                ? Optional.of(ImportTerminalOutcome.SUCCEEDED)
                : Optional.empty();
        return new ImportDelivery(
                new ImportDeliveryId("delivery-1"),
                new ImportDeliverySequence(1),
                new ImportSourceId("source-1"),
                "candidate-1",
                Optional.empty(),
                state,
                1,
                evidence,
                new ImportDeliveryRetryState(0, Optional.empty(), Optional.empty()),
                terminalOutcome,
                NOW,
                NOW);
    }

    private static final class StatefulLedger implements ImportDeliveryLedger {
        private ImportDelivery current;
        private boolean loseTerminalTransitionResponse;
        private boolean dueHeadAvailable = true;
        private int retrySchedules;
        private ImportRetrySchedule lastRetry;
        private PublishImportReportCommand lastReport;
        private ImportLedgerTransitionResult retryResult = ImportLedgerTransitionResult.APPLIED;
        private ImportLedgerTransitionResult transitionResult = ImportLedgerTransitionResult.APPLIED;
        private final List<ImportDeliveryState> transitionStates = new java.util.ArrayList<>();

        private StatefulLedger(ImportDelivery current) {
            this.current = current;
        }

        @Override
        public ImportDelivery reserveClaim(ImportClaimReservation reservation) {
            throw new AssertionError("claim reservation must not be called");
        }

        @Override
        public Optional<ImportDelivery> find(ImportDeliveryId deliveryId) {
            return current.id().equals(deliveryId) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public Optional<ImportDelivery> findHead() {
            return Optional.of(current);
        }

        @Override
        public Optional<ImportDelivery> findDueHead(Instant now) {
            return !dueHeadAvailable || current.state() == ImportDeliveryState.TERMINAL
                    ? Optional.empty()
                    : Optional.of(current);
        }

        @Override
        public ImportLedgerTransitionResult transition(ImportDeliveryTransition transition) {
            if (current.state() != transition.expectedState()
                    || current.version() != transition.expectedVersion()
                    || (transitionResult != ImportLedgerTransitionResult.APPLIED
                    && transitionResult != ImportLedgerTransitionResult.ALREADY_APPLIED)) {
                return ImportLedgerTransitionResult.CONFLICT;
            }
            transitionStates.add(transition.nextState());
            advance(transition.nextState(), transition.terminalOutcome(), transition.checkpoint());
            if (loseTerminalTransitionResponse
                    && transition.nextState() == ImportDeliveryState.TERMINAL) {
                throw new IllegalStateException("terminal transition response lost");
            }
            return transitionResult;
        }

        @Override
        public ImportLedgerTransitionResult scheduleRetry(ImportRetrySchedule schedule) {
            retrySchedules++;
            lastRetry = schedule;
            return retryResult;
        }

        @Override
        public List<ImportDelivery> findRecoverable(int limit) {
            throw new AssertionError("recovery listing must not be called");
        }

        @Override
        public List<ImportDelivery> findRetentionCandidates(
                ImportTerminalRetentionTarget target, Instant now, int limit) {
            throw new AssertionError("retention listing must not be called");
        }

        @Override
        public boolean purgeTerminal(ImportDeliveryId deliveryId, long expectedVersion) {
            throw new AssertionError("terminal purge must not be called");
        }

        private void advance(
                ImportDeliveryState state,
                Optional<ImportTerminalOutcome> terminalOutcome) {
            advance(state, terminalOutcome, ImportDeliveryCheckpoint.none());
        }

        private void advance(
                ImportDeliveryState state,
                Optional<ImportTerminalOutcome> terminalOutcome,
                ImportDeliveryCheckpoint checkpoint) {
            ImportDeliveryEvidence evidence = new ImportDeliveryEvidence(
                    checkpoint.snapshot().or(() -> current.evidence().snapshot()),
                    checkpoint.contract().or(() -> current.evidence().contract()),
                    checkpoint.stage().or(() -> current.evidence().stage()));
            current = new ImportDelivery(
                    current.id(), current.sequence(), current.sourceId(), current.candidateToken(),
                    current.replayOf(), state, current.version() + 1, evidence, current.retry(),
                    terminalOutcome, current.createdAt(), NOW);
        }
    }
}
