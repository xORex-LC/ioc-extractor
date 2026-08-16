package com.iocextractor.application.artifact.lifecycle;

/** Explicit operator decision for rows that predate lifecycle metadata. */
public enum ExistingRecordsActivationPolicy {
    REJECT,
    EXPIRE
}
