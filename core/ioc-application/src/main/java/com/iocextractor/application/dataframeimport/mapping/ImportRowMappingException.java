package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.common.IocExtractorException;

import java.util.Objects;

/** Critical mapping failure that is safe to expose without source-row values. */
public final class ImportRowMappingException extends IocExtractorException {

    /** Stable mapping failure category. */
    public enum Reason {
        PROCESSED_MODE_UNAVAILABLE
    }

    private final Reason reason;

    /** Creates a safe critical mapping failure. */
    public ImportRowMappingException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** Returns the stable mapping failure category. */
    public Reason reason() {
        return reason;
    }
}
