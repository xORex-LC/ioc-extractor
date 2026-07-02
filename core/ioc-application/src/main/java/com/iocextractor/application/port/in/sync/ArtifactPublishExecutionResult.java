package com.iocextractor.application.port.in.sync;

/** Outcome of publish work attempted by the current invocation. */
public record ArtifactPublishExecutionResult(int attempted,
                                             int succeeded,
                                             int recovered,
                                             int failed) {

    public ArtifactPublishExecutionResult {
        if (attempted < 0 || succeeded < 0 || recovered < 0 || failed < 0) {
            throw new IllegalArgumentException("publish execution counters must not be negative");
        }
        if (recovered > succeeded || succeeded + failed > attempted) {
            throw new IllegalArgumentException("publish execution counters are inconsistent");
        }
    }

    /** Returns an outcome for an invocation that had no retryable work. */
    public static ArtifactPublishExecutionResult empty() {
        return new ArtifactPublishExecutionResult(0, 0, 0, 0);
    }

    /** Returns whether this invocation attempted at least one ledger pair. */
    public boolean hasActivity() {
        return attempted > 0;
    }
}
