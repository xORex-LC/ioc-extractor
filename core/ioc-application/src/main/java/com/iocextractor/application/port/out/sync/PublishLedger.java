package com.iocextractor.application.port.out.sync;

import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishLedgerStatusCounts;
import com.iocextractor.application.sync.PublishStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable delivery saga ledger keyed by immutable slice and configured target. */
public interface PublishLedger {

    PublishRecord ensurePending(PublishRecord pending);

    Optional<PublishRecord> find(String sliceId, String targetId);

    List<PublishRecord> findBySlice(String sliceId);

    default List<PublishRecord> findBySliceName(String profile, String sliceName) {
        return findAll().stream()
                .filter(record -> record.profile().equals(profile))
                .filter(record -> record.sliceName().equals(sliceName))
                .toList();
    }

    List<PublishRecord> findRetryable();

    /**
     * Returns PENDING/FAILED rows plus stale IN_PROGRESS rows that may be recovered idempotently.
     */
    List<PublishRecord> findRetryable(Instant staleInProgressBefore);

    /** Returns aggregate counts for selected delivery pairs without materializing every row. */
    PublishLedgerStatusCounts countByStatus(Optional<String> profile,
                                            Optional<String> targetId,
                                            Optional<String> endpoint);

    /** Returns the complete delivery read model for health and reconciliation tooling. */
    List<PublishRecord> findAll();

    PublishRecord transition(String sliceId,
                             String targetId,
                             PublishStatus expected,
                             PublishStatus next,
                             String lastError,
                             String remoteVerification);
}
