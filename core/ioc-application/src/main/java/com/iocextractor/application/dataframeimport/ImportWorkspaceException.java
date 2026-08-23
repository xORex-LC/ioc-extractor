package com.iocextractor.application.dataframeimport;

import com.iocextractor.common.IocExtractorException;

import java.util.Objects;

/** Safe workspace consistency or capacity failure without paths or IOC values. */
public final class ImportWorkspaceException extends IocExtractorException {

    /** Stable failure category. */
    public enum Reason {
        CAPACITY_PAUSED,
        HARD_LIMIT_EXCEEDED,
        INCOMPATIBLE_EXISTING_STAGE,
        STAGE_NOT_SEALED,
        STAGE_INTEGRITY_FAILED,
        STORAGE_FAILURE
    }

    private final Reason reason;

    /** Creates a safe workspace failure. */
    public ImportWorkspaceException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** Creates a safe workspace failure with retained infrastructure cause. */
    public ImportWorkspaceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** Returns the stable failure category. */
    public Reason reason() {
        return reason;
    }
}
