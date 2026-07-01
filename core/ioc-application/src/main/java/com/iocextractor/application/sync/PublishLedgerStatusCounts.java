package com.iocextractor.application.sync;

/** Aggregated publish ledger read model for selected delivery pairs. */
public record PublishLedgerStatusCounts(long pending,
                                        long inProgress,
                                        long succeeded,
                                        long failed,
                                        long abandoned) {

    public PublishLedgerStatusCounts {
        requireNonNegative(pending, "pending");
        requireNonNegative(inProgress, "inProgress");
        requireNonNegative(succeeded, "succeeded");
        requireNonNegative(failed, "failed");
        requireNonNegative(abandoned, "abandoned");
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
