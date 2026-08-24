package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.DelimitedInputReadException;
import com.iocextractor.application.dataframeimport.contract.ImportRecognitionException;
import com.iocextractor.application.dataframeimport.model.ImportCommitEvidence;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportReportStore;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative single-head state machine from immutable snapshot through one
 * protected terminal unit. Every external step is idempotent, so recovery only
 * moves forward from durable evidence.
 */
public final class DataframeImportProcessingService implements ProcessNextDataframeImportUseCase {

    private final ImportDeliveryLedger ledger;
    private final DataframeImportStager staging;
    private final ProcessNextDataframeImportUseCase promotion;
    private final ImportWorkspace workspace;
    private final ImportCommitEvidenceStore commits;
    private final ImportReportStore reports;
    private final ManagedImportSourceLifecycle sources;
    private final Clock clock;
    private final Duration retryDelay;

    /** Creates one framework-free global-head processor. */
    public DataframeImportProcessingService(
            ImportDeliveryLedger ledger,
            DataframeImportStager staging,
            ProcessNextDataframeImportUseCase promotion,
            ImportWorkspace workspace,
            ImportCommitEvidenceStore commits,
            ImportReportStore reports,
            ManagedImportSourceLifecycle sources,
            Clock clock,
            Duration retryDelay) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.promotion = Objects.requireNonNull(promotion, "promotion");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("Import processing retry delay must not be negative");
        }
    }

    @Override
    public ProcessNextDataframeImportResult processNext() {
        Optional<ImportDelivery> due = ledger.findDueHead(clock.instant());
        if (due.isEmpty()) {
            return idle();
        }
        ImportDelivery head = due.orElseThrow();
        return switch (head.state()) {
            case SNAPSHOT_PINNED, CONTRACT_PINNED, STAGING -> stage(head);
            case STAGED, PROMOTING -> promote(head);
            case CANONICAL_COMMITTED, FINALIZING -> finalizeCommitted(head);
            case DETECTED, CLAIMING, CLAIMED, TERMINAL -> idle();
        };
    }

    private ProcessNextDataframeImportResult stage(ImportDelivery initial) {
        try {
            ImportDelivery current = initial;
            ImportStagingResult result;
            if (current.state() == ImportDeliveryState.SNAPSHOT_PINNED) {
                result = staging.stage(stagingCommand(current));
                current = transition(current, ImportDeliveryState.CONTRACT_PINNED,
                        ImportDeliveryCheckpoint.contract(result.contract()));
            } else {
                ImportContractPin pinned = current.contract().orElseThrow(
                        () -> contradiction("Import staging state has no pinned contract"));
                Optional<ImportStage> adopted = workspace.adoptSealed(
                        current.id(), current.snapshot().orElseThrow(), pinned);
                if (adopted.isPresent()) {
                    result = new ImportStagingResult(pinned, adopted.orElseThrow());
                } else {
                    result = requirePinned(staging.stage(stagingCommand(current)), pinned);
                }
            }
            if (current.state() == ImportDeliveryState.CONTRACT_PINNED) {
                current = transition(current, ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none());
            }
            if (current.state() == ImportDeliveryState.STAGING) {
                transition(current, ImportDeliveryState.STAGED,
                        ImportDeliveryCheckpoint.stage(result.stage()));
            }
            return performed(initial);
        } catch (ImportRecognitionException failure) {
            return reject(initial, recognitionCode(failure));
        } catch (DelimitedInputReadException failure) {
            return reject(initial, ImportDiagnosticCodes.INPUT_INVALID.id());
        } catch (ImportWorkspaceException failure) {
            return workspaceFailure(initial, failure);
        } catch (DataframeImportConsistencyException contradiction) {
            throw contradiction;
        } catch (RuntimeException failure) {
            defer(initial, ImportDiagnosticCodes.PROCESSING_FAILED.id(), true);
            return performed(initial);
        }
    }

    private ProcessNextDataframeImportResult promote(ImportDelivery delivery) {
        try {
            ProcessNextDataframeImportResult result = promotion.processNext();
            return result.workPerformed() ? result : idle();
        } catch (RuntimeException failure) {
            ImportDelivery current = required(delivery);
            return switch (current.state()) {
                case STAGED, PROMOTING -> {
                    defer(current, ImportDiagnosticCodes.PROCESSING_FAILED.id(), true);
                    yield performed(delivery);
                }
                case CANONICAL_COMMITTED, FINALIZING, TERMINAL -> performed(delivery);
                case DETECTED, CLAIMING, CLAIMED, SNAPSHOT_PINNED, CONTRACT_PINNED, STAGING ->
                        throw contradiction(
                                "Import promotion failed after an invalid durable state change", failure);
            };
        }
    }

    private ProcessNextDataframeImportResult finalizeCommitted(ImportDelivery initial) {
        try {
            ImportDelivery current = initial;
            if (current.state() == ImportDeliveryState.CANONICAL_COMMITTED) {
                current = transition(current, ImportDeliveryState.FINALIZING,
                        ImportDeliveryCheckpoint.none());
            }
            ImportCommitEvidence evidence = commits.find(current.id()).orElseThrow(
                    () -> contradiction("Canonical import service state has no dataframe receipt"));
            ImportTerminalOutcome outcome = evidence.terminalOutcome();
            reports.publish(report(current, outcome, evidence.acceptedRows(), evidence.rejectedRows(),
                    evidence.publicMutations(), evidence.affectedArtifacts(), List.of(), evidence.issues()));
            sources.disposition(new DispositionImportSourceCommand(
                    current.id(), current.sourceId(), outcome));
            transition(current, ImportDeliveryState.TERMINAL, ImportDeliveryCheckpoint.none(), outcome);
            return performed(initial);
        } catch (DataframeImportConsistencyException contradiction) {
            throw contradiction;
        } catch (RuntimeException failure) {
            ImportDelivery current = required(initial);
            return switch (current.state()) {
                case CANONICAL_COMMITTED, FINALIZING -> {
                    defer(current, ImportDiagnosticCodes.FINALIZATION_FAILED.id(), true);
                    yield performed(initial);
                }
                case TERMINAL -> performed(initial);
                case DETECTED, CLAIMING, CLAIMED, SNAPSHOT_PINNED, CONTRACT_PINNED,
                        STAGING, STAGED, PROMOTING -> throw contradiction(
                                "Import finalization failed after an invalid durable state change", failure);
            };
        }
    }

    private ProcessNextDataframeImportResult reject(ImportDelivery initial, String code) {
        try {
            ImportDelivery current = required(initial);
            reports.publish(report(current, ImportTerminalOutcome.REJECTED,
                    0, 0, 0, Set.of(), List.of(code), List.of()));
            sources.disposition(new DispositionImportSourceCommand(
                    current.id(), current.sourceId(), ImportTerminalOutcome.REJECTED));
            transition(current, ImportDeliveryState.TERMINAL,
                    ImportDeliveryCheckpoint.none(), ImportTerminalOutcome.REJECTED);
            return performed(initial);
        } catch (DataframeImportConsistencyException contradiction) {
            throw contradiction;
        } catch (RuntimeException failure) {
            ImportDelivery current = required(initial);
            if (current.state() == ImportDeliveryState.TERMINAL) {
                return performed(initial);
            }
            defer(current, ImportDiagnosticCodes.FINALIZATION_FAILED.id(), true);
            return performed(initial);
        }
    }

    private ProcessNextDataframeImportResult workspaceFailure(
            ImportDelivery delivery, ImportWorkspaceException failure) {
        return switch (failure.reason()) {
            case CAPACITY_PAUSED -> {
                defer(required(delivery), ImportDiagnosticCodes.CAPACITY_PAUSED.id(), false);
                yield performed(delivery);
            }
            case HARD_LIMIT_EXCEEDED -> reject(delivery, ImportDiagnosticCodes.LIMIT_EXCEEDED.id());
            case STAGE_INTEGRITY_FAILED, STAGE_NOT_SEALED ->
                    throw contradiction("Import sealed-stage evidence is contradictory");
            case INCOMPATIBLE_EXISTING_STAGE, STORAGE_FAILURE -> {
                defer(required(delivery), ImportDiagnosticCodes.PROCESSING_FAILED.id(), true);
                yield performed(delivery);
            }
        };
    }

    private ImportStagingCommand stagingCommand(ImportDelivery delivery) {
        return new ImportStagingCommand(
                delivery.id(), delivery.sourceId(), delivery.snapshot().orElseThrow(
                        () -> contradiction("Import staging state has no pinned snapshot")));
    }

    private ImportStagingResult requirePinned(ImportStagingResult result, ImportContractPin pinned) {
        if (!result.contract().equals(pinned)) {
            throw contradiction("Pinned import contract is unavailable after restart");
        }
        return result;
    }

    private PublishImportReportCommand report(
            ImportDelivery delivery,
            ImportTerminalOutcome outcome,
            long acceptedRows,
            long rejectedRows,
            long publicMutations,
            Set<String> affectedArtifacts,
            List<String> deliveryCodes,
            List<ImportRowIssue> issues) {
        return new PublishImportReportCommand(
                delivery.id(), delivery.sourceId(),
                delivery.snapshot().orElseThrow(
                        () -> contradiction("Import finalization has no pinned snapshot")).reference(),
                delivery.contract(), outcome, acceptedRows, rejectedRows, publicMutations,
                affectedArtifacts, deliveryCodes, issues);
    }

    private ImportDelivery transition(ImportDelivery current,
                                      ImportDeliveryState next,
                                      ImportDeliveryCheckpoint checkpoint) {
        return transition(current, next, checkpoint, null);
    }

    private ImportDelivery transition(ImportDelivery current,
                                      ImportDeliveryState next,
                                      ImportDeliveryCheckpoint checkpoint,
                                      ImportTerminalOutcome outcome) {
        ImportDeliveryTransition request = new ImportDeliveryTransition(
                current.id(), current.state(), current.version(), next,
                Optional.ofNullable(outcome), checkpoint, Optional.empty(), clock.instant());
        ImportLedgerTransitionResult result = ledger.transition(request);
        if (result != ImportLedgerTransitionResult.APPLIED
                && result != ImportLedgerTransitionResult.ALREADY_APPLIED) {
            throw contradiction("Import processing transition conflicts with durable state");
        }
        return required(current);
    }

    private void defer(ImportDelivery delivery, String code, boolean failedAttempt) {
        Instant now = clock.instant();
        ImportRetrySchedule schedule = new ImportRetrySchedule(
                delivery.id(), delivery.state(), delivery.version(), now.plus(retryDelay),
                code, failedAttempt, now);
        ImportLedgerTransitionResult result = ledger.scheduleRetry(schedule);
        if (result != ImportLedgerTransitionResult.APPLIED
                && result != ImportLedgerTransitionResult.ALREADY_APPLIED) {
            throw contradiction("Import retry scheduling conflicts with durable state");
        }
    }

    private ImportDelivery required(ImportDelivery delivery) {
        return ledger.find(delivery.id()).orElseThrow(
                () -> contradiction("Import delivery disappeared during processing"));
    }

    private String recognitionCode(ImportRecognitionException failure) {
        return switch (failure.reason()) {
            case SOURCE_NOT_CONFIGURED -> "IMPORT.SOURCE_NOT_CONFIGURED";
            case CONTRACT_NOT_RECOGNIZED -> ImportDiagnosticCodes.CONTRACT_NOT_RECOGNIZED.id();
            case CONTRACT_AMBIGUOUS -> ImportDiagnosticCodes.CONTRACT_AMBIGUOUS.id();
        };
    }

    private ProcessNextDataframeImportResult performed(ImportDelivery delivery) {
        return new ProcessNextDataframeImportResult(true, Optional.of(delivery.id()));
    }

    private ProcessNextDataframeImportResult idle() {
        return new ProcessNextDataframeImportResult(false, Optional.empty());
    }

    private DataframeImportConsistencyException contradiction(String message) {
        return new DataframeImportConsistencyException(message);
    }

    private DataframeImportConsistencyException contradiction(
            String message, RuntimeException cause) {
        return new DataframeImportConsistencyException(message, cause);
    }
}
