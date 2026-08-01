package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
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

    /** Creates the initial claim without overwriting an existing state. */
    IngestionLedgerTransition markClaimed(SourceUnit unit);

    /** Transitions {@code CLAIMED -> SOURCE_ARCHIVED}. */
    IngestionLedgerTransition markSourceArchived(SourceKey key, Path archivedPath);

    /** Transitions {@code CLAIMED -> FAILED}, or records a pre-claim failure. */
    IngestionLedgerTransition markFailed(SourceKey key, String reason);

    List<IngestionRecord> findIncomplete();
}
