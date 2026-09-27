package com.iocextractor.adapter.processing.camel.contract;

import java.util.List;
import java.util.Objects;

/** Ordered selection evidence, resolved before any destination is invoked. */
public record PlanSelection(Status status, List<String> selectedBranches,
                            List<BlockedBranch> blockedBranches,
                            List<String> ambiguousBranches) {
    public PlanSelection {
        Objects.requireNonNull(status);
        selectedBranches = List.copyOf(selectedBranches);
        blockedBranches = List.copyOf(blockedBranches);
        ambiguousBranches = List.copyOf(ambiguousBranches);
    }

    /** The outcome of selection, including both explicit no-match actions. */
    public enum Status { MATCHED, SKIPPED, REJECTED, BLOCKED, AMBIGUOUS }

    /** A reached branch whose eligibility could not be determined. */
    public record BlockedBranch(String branchId, FailureReference failure) {
        public BlockedBranch {
            Objects.requireNonNull(branchId);
            Objects.requireNonNull(failure);
        }
    }
}
