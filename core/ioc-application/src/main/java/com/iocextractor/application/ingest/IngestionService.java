package com.iocextractor.application.ingest;

import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import com.iocextractor.application.aggregation.NoopArtifactProjection;
import com.iocextractor.application.aggregation.NoopRunLedger;
import com.iocextractor.application.port.out.aggregation.ArtifactProjection;
import com.iocextractor.application.port.out.aggregation.RunLedger;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.port.out.ingest.SourceSinkFactory;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.common.IocExtractorException;

import java.nio.file.Path;
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
    private final SourceSinkFactory sourceSinkFactory;
    private final IocExtractionServiceFactory extractionFactory;
    private final RunLedger runLedger;
    private final ArtifactProjection projection;

    public IngestionService(IngestionLedger ledger,
                            SourceLifecycle sourceLifecycle,
                            SourceSinkFactory sourceSinkFactory,
                            IocExtractionServiceFactory extractionFactory) {
        this(ledger, sourceLifecycle, sourceSinkFactory, extractionFactory,
                new NoopRunLedger(), new NoopArtifactProjection());
    }

    public IngestionService(IngestionLedger ledger,
                            SourceLifecycle sourceLifecycle,
                            SourceSinkFactory sourceSinkFactory,
                            IocExtractionServiceFactory extractionFactory,
                            RunLedger runLedger,
                            ArtifactProjection projection) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sourceLifecycle = Objects.requireNonNull(sourceLifecycle, "sourceLifecycle");
        this.sourceSinkFactory = Objects.requireNonNull(sourceSinkFactory, "sourceSinkFactory");
        this.extractionFactory = Objects.requireNonNull(extractionFactory, "extractionFactory");
        this.runLedger = Objects.requireNonNull(runLedger, "runLedger");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public IngestSourceResult ingest(IngestSourceCommand command) {
        Objects.requireNonNull(command, "command");
        var existing = ledger.find(command.key());
        if (existing.isPresent()) {
            return handleExisting(command, existing.get());
        }

        SourceUnit unit = sourceLifecycle.claim(command.source(), command.key(), command.detectedAt());
        try {
            ledger.markClaimed(unit);
        } catch (RuntimeException e) {
            sourceLifecycle.fail(unit, e.getMessage());
            ledger.markFailed(command.key(), e.getMessage());
            throw e;
        }
        return processClaimed(unit);
    }

    @Override
    public List<IngestSourceResult> recoverIncomplete() {
        List<IngestSourceResult> results = new ArrayList<>();
        for (IngestionRecord record : ledger.findIncomplete()) {
            results.add(recover(record));
        }
        for (ArchivedSourceUnit orphan : sourceLifecycle.findProcessingSources()) {
            if (ledger.find(orphan.key()).isEmpty()) {
                sourceLifecycle.fail(orphan, "orphan processing source without ledger record");
                ledger.markFailed(orphan.key(), "orphan processing source without ledger record");
                results.add(new IngestSourceResult(orphan.key(), IngestionStatus.FAILED, false, null));
            }
        }
        return results;
    }

    @Override
    public void reject(SourceKey key, String reason) {
        Objects.requireNonNull(key, "key");
        var record = ledger.find(key);
        if (record.isPresent()) {
            failRecord(record.get(), reason);
        }
        ledger.markFailed(key, reason);
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

    private IngestSourceResult recover(IngestionRecord record) {
        return switch (record.status()) {
            case CLAIMED -> processClaimed(new SourceUnit(
                    record.key(), record.originalPath(), record.processingPath(), record.detectedAt()));
            case FAILED, SOURCE_ARCHIVED -> new IngestSourceResult(record.key(), record.status(), false, null);
        };
    }

    private IngestSourceResult processClaimed(SourceUnit unit) {
        var sourceSinks = sourceSinkFactory.createFor(unit);
        var run = runLedger.startIngest(unit.key().value(), sourceSinks.artifactNames());
        boolean dbCommitted = false;
        ExtractionResult extraction;
        try {
            extraction = extractionFactory.create(sourceSinks.sinks())
                .extract(new ExtractionCommand(unit.processingPath(), false));
            runLedger.markDbCommitted(run.runId());
            dbCommitted = true;
            for (String artifactName : sourceSinks.artifactNames()) {
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
        return new IngestSourceResult(unit.key(), IngestionStatus.SOURCE_ARCHIVED, false, extraction);
    }

    private void failRecord(IngestionRecord record, String reason) {
        if (record.processingPath() == null) {
            return;
        }
        var source = new ArchivedSourceUnit(record.key(), record.processingPath(), record.detectedAt());
        sourceLifecycle.fail(source, reason);
    }
}
