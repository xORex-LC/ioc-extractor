package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Durable status ledger for whole-file ingestion.
 */
public interface IngestionLedger {

    Optional<IngestionRecord> find(SourceKey key);

    void markClaimed(SourceUnit unit);

    void markPartitionWritten(SourceKey key, List<Path> partitions);

    void markLedgerRecorded(SourceKey key);

    void markSourceArchived(SourceKey key, Path archivedPath);

    void markFailed(SourceKey key, String reason);

    List<IngestionRecord> findIncomplete();
}
