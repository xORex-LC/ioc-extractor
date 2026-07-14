package com.iocextractor.application.ingest;

import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import com.iocextractor.application.artifact.NoopArtifactProjection;
import com.iocextractor.application.artifact.NoopRunLedger;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.port.out.ingest.SourcePreparerFactory;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.events.NoopControlEventPublisher;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Application orchestration for whole-file ingest. It coordinates source
 * ownership, durable status updates and the existing IOC extraction pipeline;
 * adapters remain responsible for file discovery, hashing and physical storage.
 */
public final class IngestionService implements IngestSourceUseCase, RecoverIngestionUseCase, RejectIngestionUseCase {

    private final IngestionLedger ledger;
    private final SourceLifecycle sourceLifecycle;
    private final SourcePreparerFactory sourcePreparerFactory;
    private final IocExtractionServiceFactory extractionFactory;
    private final RunLedger runLedger;
    private final ArtifactProjection projection;
    private final ControlEventPublisher eventPublisher;
    private final Clock clock;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnostics;

    public IngestionService(IngestionLedger ledger,
                            SourceLifecycle sourceLifecycle,
                            SourcePreparerFactory sourcePreparerFactory,
                            IocExtractionServiceFactory extractionFactory) {
        this(ledger, sourceLifecycle, sourcePreparerFactory, extractionFactory,
                new NoopRunLedger(), NoopArtifactProjection.INSTANCE);
    }

    public IngestionService(IngestionLedger ledger,
                            SourceLifecycle sourceLifecycle,
                            SourcePreparerFactory sourcePreparerFactory,
                            IocExtractionServiceFactory extractionFactory,
                            RunLedger runLedger,
                            ArtifactProjection projection) {
        this(ledger, sourceLifecycle, sourcePreparerFactory, extractionFactory, runLedger, projection,
                NoopControlEventPublisher.INSTANCE, Clock.systemUTC(), NoopDiagnosticSink.INSTANCE);
    }

    public IngestionService(IngestionLedger ledger,
                            SourceLifecycle sourceLifecycle,
                            SourcePreparerFactory sourcePreparerFactory,
                            IocExtractionServiceFactory extractionFactory,
                            RunLedger runLedger,
                            ArtifactProjection projection,
                            ControlEventPublisher eventPublisher,
                            Clock clock) {
        this(ledger, sourceLifecycle, sourcePreparerFactory, extractionFactory, runLedger, projection,
                eventPublisher, clock, NoopDiagnosticSink.INSTANCE);
    }

    public IngestionService(IngestionLedger ledger,
                            SourceLifecycle sourceLifecycle,
                            SourcePreparerFactory sourcePreparerFactory,
                            IocExtractionServiceFactory extractionFactory,
                            RunLedger runLedger,
                            ArtifactProjection projection,
                            ControlEventPublisher eventPublisher,
                            Clock clock,
                            DiagnosticSink diagnosticSink) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sourceLifecycle = Objects.requireNonNull(sourceLifecycle, "sourceLifecycle");
        this.sourcePreparerFactory = Objects.requireNonNull(sourcePreparerFactory, "sourcePreparerFactory");
        this.extractionFactory = Objects.requireNonNull(extractionFactory, "extractionFactory");
        this.runLedger = Objects.requireNonNull(runLedger, "runLedger");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnostics = new DiagnosticFactory(clock);
    }

    @Override
    public IngestSourceResult ingest(IngestSourceCommand command) {
        Objects.requireNonNull(command, "command");
        var existing = ledger.find(command.key());
        if (existing.isPresent()) {
            return handleExisting(command, existing.get());
        }

        SourceUnit unit = claim(command);
        try {
            ledger.markClaimed(unit);
        } catch (RuntimeException e) {
            var failure = ledgerFailure(command.key(), "mark-claimed", e);
            try {
                sourceLifecycle.fail(unit, e.getMessage());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                ledger.markFailed(command.key(), e.getMessage());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return processClaimed(unit);
    }

    @Override
    public List<IngestSourceResult> recoverIncomplete() {
        try {
            List<IngestSourceResult> results = new ArrayList<>();
            for (IngestionRecord record : ledger.findIncomplete()) {
                try {
                    results.add(recover(record));
                } catch (RuntimeException failure) {
                    if (isRecoveryFailure(failure)) {
                        throw failure;
                    }
                    throw recoveryFailure(record.key(), failure);
                }
            }
            for (ArchivedSourceUnit orphan : sourceLifecycle.findProcessingSources()) {
                recoverOrphan(orphan, results);
            }
            return results;
        } catch (RuntimeException failure) {
            if (!isRecoveryFailure(failure)) {
                throw recoveryFailure(new SourceKey("recovery-scan"), failure);
            }
            throw failure;
        }
    }

    @Override
    public void reject(SourceKey key, String reason) {
        Objects.requireNonNull(key, "key");
        var record = ledger.find(key);
        if (record.isPresent()) {
            try {
                failRecord(record.get(), reason);
            } catch (RuntimeException failure) {
                throw new DiagnosticException(diagnostics.create(IngestDiagnosticCodes.DEAD_LETTER_FAILED)
                        .with("source", key.value())
                        .with("reason", reason(failure))
                        .cause(failure)
                        .build());
            }
        }
        try {
            ledger.markFailed(key, reason);
        } catch (RuntimeException failure) {
            throw ledgerFailure(key, "mark-failed", failure);
        }
    }

    private IngestSourceResult handleExisting(IngestSourceCommand command, IngestionRecord record) {
        if (record.status() == IngestionStatus.SOURCE_ARCHIVED) {
            sourceLifecycle.archiveDuplicate(command.source(), command.key());
            return new IngestSourceResult(command.key(), record.status(), true, null);
        }
        if (record.status() == IngestionStatus.FAILED) {
            return new IngestSourceResult(command.key(), IngestionStatus.FAILED, false, null);
        }
        return recover(record);
    }

    private SourceUnit claim(IngestSourceCommand command) {
        try {
            return sourceLifecycle.claim(command.source(), command.key(), command.detectedAt());
        } catch (RuntimeException failure) {
            throw new DiagnosticException(diagnostics.create(IngestDiagnosticCodes.CLAIM_FAILED)
                    .with("source", command.source())
                    .with("reason", reason(failure))
                    .cause(failure)
                    .build());
        }
    }

    private void recoverOrphan(ArchivedSourceUnit orphan, List<IngestSourceResult> results) {
        if (ledger.find(orphan.key()).isPresent()) {
            return;
        }
        try {
            String reason = "orphan processing source without ledger record";
            sourceLifecycle.fail(orphan, reason);
            ledger.markFailed(orphan.key(), reason);
            results.add(new IngestSourceResult(orphan.key(), IngestionStatus.FAILED, false, null));
        } catch (RuntimeException failure) {
            throw recoveryFailure(orphan.key(), failure);
        }
    }

    private DiagnosticException ledgerFailure(SourceKey key, String operation, RuntimeException failure) {
        return new DiagnosticException(diagnostics.create(IngestDiagnosticCodes.LEDGER_WRITE_FAILED)
                .with("source", key.value())
                .with("operation", operation)
                .with("reason", reason(failure))
                .cause(failure)
                .build());
    }

    private DiagnosticException recoveryFailure(SourceKey key, RuntimeException failure) {
        var diagnostic = diagnostics.create(IngestDiagnosticCodes.RECOVERY_FAILED)
                .with("source", key.value())
                .with("reason", reason(failure))
                .cause(failure)
                .build();
        diagnosticSink.emit(diagnostic);
        return new DiagnosticException(diagnostic);
    }

    private boolean isRecoveryFailure(RuntimeException failure) {
        return failure instanceof DiagnosticException diagnosticFailure
                && diagnosticFailure.diagnostic().code() == IngestDiagnosticCodes.RECOVERY_FAILED;
    }

    private String reason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private IngestSourceResult recover(IngestionRecord record) {
        return switch (record.status()) {
            case CLAIMED -> processClaimed(new SourceUnit(
                    record.key(), record.originalPath(), record.processingPath(), record.detectedAt()));
            case FAILED, SOURCE_ARCHIVED -> new IngestSourceResult(record.key(), record.status(), false, null);
        };
    }

    private IngestSourceResult processClaimed(SourceUnit unit) {
        var sourcePreparers = sourcePreparerFactory.createFor(unit);
        var run = runLedger.startIngest(unit.key().value(), sourcePreparers.artifactNames());
        boolean dbCommitted = false;
        ExtractionResult extraction;
        try {
            extraction = extractionFactory.create(sourcePreparers.preparers(), NoopArtifactProjection.INSTANCE)
                    .extract(new ExtractionCommand(run.runId(), unit.processingPath(), false));
            runLedger.markDbCommitted(run.runId());
            dbCommitted = true;
            for (String artifactName : sourcePreparers.artifactNames()) {
                projection.project(artifactName);
            }
            runLedger.markProjectionCompleted(run.runId());
        } catch (RuntimeException e) {
            if (!dbCommitted) {
                runLedger.markFailed(run.runId(), e.getMessage());
            }
            throw e;
        }
        Path archived = sourceLifecycle.archive(unit);
        ledger.markSourceArchived(unit.key(), archived);
        runLedger.markCompleted(run.runId());
        publishArtifactsChanged(run.runId(), run.artifacts());
        return new IngestSourceResult(unit.key(), IngestionStatus.SOURCE_ARCHIVED, false, extraction);
    }

    private void publishArtifactsChanged(String runId, List<String> artifactNames) {
        try {
            eventPublisher.publish(CanonicalArtifactsChanged.from(runId, artifactNames, clock.instant()));
        } catch (RuntimeException ignored) {
            // Event delivery must not affect the durable ingest outcome.
        }
    }

    private void failRecord(IngestionRecord record, String reason) {
        if (record.processingPath() == null) {
            return;
        }
        var source = new ArchivedSourceUnit(record.key(), record.processingPath(), record.detectedAt());
        sourceLifecycle.fail(source, reason);
    }
}
