package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.DelimitedInputReadException;
import com.iocextractor.application.dataframeimport.contract.ImportRecognitionException;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportCommitEvidence;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
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

class DataframeImportProcessingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DIGEST = "a".repeat(64);

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
        return new DataframeImportProcessingService(
                ledger,
                stager,
                promotion,
                unusedWorkspace(),
                commitEvidenceStore(ledger.current.id()),
                reports,
                unusedSourceLifecycle(),
                CLOCK,
                Duration.ofSeconds(5));
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
        ImportContractPin contract = new ImportContractPin(
                new ImportContractId("ip-list-v1"), 1, new ImportContractFingerprint(DIGEST));
        ImportStage stage = new ImportStage(
                new ImportStageReference("stage:test"), new ImportSha256(DIGEST), 1, 1, 0);
        ImportDeliveryEvidence evidence = switch (state) {
            case SNAPSHOT_PINNED -> new ImportDeliveryEvidence(
                    Optional.of(snapshot), Optional.empty(), Optional.empty());
            case STAGED, CANONICAL_COMMITTED -> new ImportDeliveryEvidence(
                    Optional.of(snapshot), Optional.of(contract), Optional.of(stage));
            default -> throw new IllegalArgumentException("Unsupported test delivery state");
        };
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
                Optional.empty(),
                NOW,
                NOW);
    }

    private static final class StatefulLedger implements ImportDeliveryLedger {
        private ImportDelivery current;
        private boolean loseTerminalTransitionResponse;
        private int retrySchedules;

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
            return current.state() == ImportDeliveryState.TERMINAL
                    ? Optional.empty()
                    : Optional.of(current);
        }

        @Override
        public ImportLedgerTransitionResult transition(ImportDeliveryTransition transition) {
            if (current.state() != transition.expectedState()
                    || current.version() != transition.expectedVersion()) {
                return ImportLedgerTransitionResult.CONFLICT;
            }
            advance(transition.nextState(), transition.terminalOutcome());
            if (loseTerminalTransitionResponse
                    && transition.nextState() == ImportDeliveryState.TERMINAL) {
                throw new IllegalStateException("terminal transition response lost");
            }
            return ImportLedgerTransitionResult.APPLIED;
        }

        @Override
        public ImportLedgerTransitionResult scheduleRetry(ImportRetrySchedule schedule) {
            retrySchedules++;
            return ImportLedgerTransitionResult.APPLIED;
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
            current = new ImportDelivery(
                    current.id(), current.sequence(), current.sourceId(), current.candidateToken(),
                    current.replayOf(), state, current.version() + 1, current.evidence(), current.retry(),
                    terminalOutcome, current.createdAt(), NOW);
        }
    }
}
