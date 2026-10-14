package com.iocextractor.application.ingest.admission;

import java.time.Instant;
import java.util.Objects;

/** Durable per-document execution attempts and backoff; never per-IOC control state. */
public record DocumentExecutionState(int attempts, Instant retryAfter, String failure) {
    public static final DocumentExecutionState NEW = new DocumentExecutionState(0, Instant.EPOCH, null);
    public DocumentExecutionState {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (attempts < 0) { throw new IllegalArgumentException("Attempts must be nonnegative"); }
    }
}
