package com.iocextractor.application.sync;

/** Durable per-slice/per-target publish status. */
public enum PublishStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    ABANDONED
}
