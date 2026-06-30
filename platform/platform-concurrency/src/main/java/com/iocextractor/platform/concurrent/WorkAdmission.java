package com.iocextractor.platform.concurrent;

import java.util.Objects;

/**
 * Result of an in-memory keyed work admission attempt.
 *
 * @param key work key used for admission
 * @param status accepted or rejected
 * @param queuedDepth queued work count for the key after the decision, excluding running work
 */
public record WorkAdmission(WorkKey key, WorkAdmissionStatus status, int queuedDepth) {

    public WorkAdmission {
        key = Objects.requireNonNull(key, "key");
        status = Objects.requireNonNull(status, "status");
        if (queuedDepth < 0) {
            throw new IllegalArgumentException("queuedDepth must not be negative");
        }
    }

    /** Creates an accepted admission result. */
    public static WorkAdmission accepted(WorkKey key, int queuedDepth) {
        return new WorkAdmission(key, WorkAdmissionStatus.ACCEPTED, queuedDepth);
    }

    /** Creates a rejected admission result. */
    public static WorkAdmission rejected(WorkKey key, int queuedDepth) {
        return new WorkAdmission(key, WorkAdmissionStatus.REJECTED, queuedDepth);
    }

    /** Returns true when the work was accepted for execution. */
    public boolean accepted() {
        return status == WorkAdmissionStatus.ACCEPTED;
    }
}
