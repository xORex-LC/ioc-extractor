package com.iocextractor.application.ingest.admission;

import java.time.Instant;
import java.util.Objects;

/** Durable per-document execution attempts and backoff; never per-IOC control state. */
public record DocumentExecutionState(int attempts, Instant retryAfter, String failure) {
    public static final Instant HELD = Instant.ofEpochMilli(Long.MAX_VALUE);
    public static final DocumentExecutionState NEW = new DocumentExecutionState(0, Instant.EPOCH, null);
    /** Operational reasons safe to display regardless of an older journal's free-text failure. */
    public enum Failure {
        CLAIM_UNCONFIRMED,
        CANDIDATE_REPLACED,
        PROMOTION_OUTCOME_UNKNOWN,
        PROMOTION_RECOVERY_FAILED,
        REJECTION_OWNERSHIP_UNCONFIRMED,
        REJECTION_FILES_MISSING,
        REJECTION_TARGET_CONFLICT,
        REJECTION_INCOMPLETE;

        /** Filters persisted text through the current fixed vocabulary. */
        public static boolean isKnownCode(String value) {
            for (var failure : values()) {
                if (failure.name().equals(value)) { return true; }
            }
            for (var code : DocumentRejection.Code.values()) {
                if (code.name().equals(value)) { return true; }
            }
            return false;
        }
    }

    public DocumentExecutionState {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (attempts < 0) { throw new IllegalArgumentException("Attempts must be nonnegative"); }
    }
}
