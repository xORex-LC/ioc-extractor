package com.iocextractor.application.port.in.sync;

/** Operator-facing summary of one publish/reconcile cycle. */
public record ArtifactPublishResult(int pending, int succeeded, int failed, int abandoned) {

    public ArtifactPublishResult {
        if (pending < 0 || succeeded < 0 || failed < 0 || abandoned < 0) {
            throw new IllegalArgumentException("publish counters must not be negative");
        }
    }
}
