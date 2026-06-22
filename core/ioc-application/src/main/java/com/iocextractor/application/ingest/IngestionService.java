package com.iocextractor.application.ingest;

import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.PartitionSinkFactory;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
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
    private final PartitionSinkFactory partitionSinkFactory;
    private final IocExtractionServiceFactory extractionFactory;

    public IngestionService(IngestionLedger ledger,
                            SourceLifecycle sourceLifecycle,
                            PartitionSinkFactory partitionSinkFactory,
                            IocExtractionServiceFactory extractionFactory) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sourceLifecycle = Objects.requireNonNull(sourceLifecycle, "sourceLifecycle");
        this.partitionSinkFactory = Objects.requireNonNull(partitionSinkFactory, "partitionSinkFactory");
        this.extractionFactory = Objects.requireNonNull(extractionFactory, "extractionFactory");
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
                results.add(new IngestSourceResult(orphan.key(), IngestionStatus.FAILED, false, List.of(), null));
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
        if (record.status() == IngestionStatus.SOURCE_ARCHIVED || record.status() == IngestionStatus.AGGREGATED) {
            sourceLifecycle.archiveDuplicate(command.source(), command.key());
            return new IngestSourceResult(command.key(), record.status(),
                    true, record.partitions(), null);
        }
        if (record.status() == IngestionStatus.FAILED) {
            return new IngestSourceResult(command.key(), IngestionStatus.FAILED,
                    false, record.partitions(), null);
        }
        return recover(record);
    }

    private IngestSourceResult recover(IngestionRecord record) {
        return switch (record.status()) {
            case CLAIMED -> processClaimed(new SourceUnit(
                    record.key(), record.originalPath(), record.processingPath(), record.detectedAt()));
            case PARTITION_WRITTEN -> completeAfterPartitionWrite(record);
            case LEDGER_RECORDED -> archiveRecorded(record);
            case FAILED, SOURCE_ARCHIVED, AGGREGATED -> new IngestSourceResult(record.key(), record.status(),
                    false, record.partitions(), null);
        };
    }

    private IngestSourceResult processClaimed(SourceUnit unit) {
        var partitionSinks = partitionSinkFactory.createFor(unit);
        ExtractionResult extraction = extractionFactory.create(partitionSinks.sinks())
                .extract(new ExtractionCommand(unit.processingPath(), false));
        ledger.markPartitionWritten(unit.key(), partitionSinks.paths());
        ledger.markLedgerRecorded(unit.key());
        Path archived = sourceLifecycle.archive(unit);
        ledger.markSourceArchived(unit.key(), archived);
        return new IngestSourceResult(unit.key(), IngestionStatus.SOURCE_ARCHIVED,
                false, partitionSinks.paths(), extraction);
    }

    private IngestSourceResult completeAfterPartitionWrite(IngestionRecord record) {
        ledger.markLedgerRecorded(record.key());
        return archiveRecorded(record);
    }

    private IngestSourceResult archiveRecorded(IngestionRecord record) {
        if (record.processingPath() == null) {
            throw new IocExtractorException("Cannot recover ingestion record without processing path: "
                    + record.key().value());
        }
        Path archived = sourceLifecycle.archive(new ArchivedSourceUnit(
                record.key(), record.processingPath(), record.detectedAt()));
        ledger.markSourceArchived(record.key(), archived);
        return new IngestSourceResult(record.key(), IngestionStatus.SOURCE_ARCHIVED,
                false, record.partitions(), null);
    }

    private void failRecord(IngestionRecord record, String reason) {
        if (record.processingPath() == null) {
            return;
        }
        var source = new ArchivedSourceUnit(record.key(), record.processingPath(), record.detectedAt());
        sourceLifecycle.fail(source, reason);
    }
}
