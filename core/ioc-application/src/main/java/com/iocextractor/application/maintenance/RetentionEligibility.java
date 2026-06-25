package com.iocextractor.application.maintenance;

import java.util.List;

/**
 * Policy hook that decides which listed entries may be considered by retention.
 * The default is allow-all; daemon partitions can use ledger state to avoid
 * reaping data that has not been aggregated yet.
 */
public interface RetentionEligibility {

    /**
     * Filters retention candidates before age/count policy is applied.
     *
     * @param target configured retention target
     * @param entries entries listed from the target storage
     * @return entries allowed to be reaped by retention policy
     */
    List<RetentionEntry> eligibleEntries(RetentionTarget target, List<RetentionEntry> entries);

    /**
     * Default policy for targets that do not need cross-state gating.
     */
    static RetentionEligibility allowAll() {
        return (target, entries) -> List.copyOf(entries);
    }
}
