package com.iocextractor.application.port.out.sync;

import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishLedgerHealthSummary;
import com.iocextractor.application.sync.PublishLedgerStatusCounts;
import com.iocextractor.application.sync.PublishStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Returns aggregate durable health state for the configured target set. */
    default PublishLedgerHealthSummary healthSummary(Set<String> targetIds) {
        Map<String, EnumMap<PublishStatus, Long>> byEndpoint = new LinkedHashMap<>();
        EnumMap<PublishStatus, Long> totals = new EnumMap<>(PublishStatus.class);
        findAll().stream()
                .filter(record -> targetIds.contains(record.targetId()))
                .forEach(record -> {
                    totals.merge(record.status(), 1L, Long::sum);
                    byEndpoint.computeIfAbsent(record.endpoint(), ignored -> new EnumMap<>(PublishStatus.class))
                            .merge(record.status(), 1L, Long::sum);
                });
        Map<String, PublishLedgerStatusCounts> endpointCounts = new LinkedHashMap<>();
        byEndpoint.forEach((endpoint, counts) -> endpointCounts.put(endpoint, counts(counts)));
        return new PublishLedgerHealthSummary(counts(totals), endpointCounts);
    }

    /** Returns the complete delivery read model for health and reconciliation tooling. */
    List<PublishRecord> findAll();

    PublishRecord transition(String sliceId,
                             String targetId,
                             PublishStatus expected,
                             PublishStatus next,
                             String lastError,
                             String remoteVerification);

    private static PublishLedgerStatusCounts counts(Map<PublishStatus, Long> counts) {
        return new PublishLedgerStatusCounts(
                counts.getOrDefault(PublishStatus.PENDING, 0L),
                counts.getOrDefault(PublishStatus.IN_PROGRESS, 0L),
                counts.getOrDefault(PublishStatus.SUCCEEDED, 0L),
                counts.getOrDefault(PublishStatus.FAILED, 0L),
                counts.getOrDefault(PublishStatus.ABANDONED, 0L));
    }
}
