package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Durable status ledger for whole-file ingestion.
 */
public interface IngestionLedger {

    Optional<IngestionRecord> find(ObservationId observationId);

    /** Compatibility lookup for a legacy content-keyed attempt. */
    default Optional<IngestionRecord> find(SourceKey key) {
        return find(ObservationId.legacy(key.value()));
    }

    /** Creates the initial claim without overwriting an existing state. */
    IngestionLedgerTransition markClaimed(SourceUnit unit);

    /** Transitions {@code CLAIMED -> SOURCE_ARCHIVED}. */
    IngestionLedgerTransition markSourceArchived(ObservationId observationId, Path archivedPath);

    default IngestionLedgerTransition markSourceArchived(SourceKey key, Path archivedPath) {
        return markSourceArchived(ObservationId.legacy(key.value()), archivedPath);
    }

    /** Transitions {@code CLAIMED -> FAILED}, or records a pre-claim failure. */
    IngestionLedgerTransition markFailed(ObservationId observationId, SourceKey key, String reason);

    default IngestionLedgerTransition markFailed(SourceKey key, String reason) {
        return markFailed(ObservationId.legacy(key.value()), key, reason);
    }

    List<IngestionRecord> findIncomplete();
}
