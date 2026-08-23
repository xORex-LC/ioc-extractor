package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.common.IocExtractorException;

import java.util.Objects;

/** Critical exact-one recognition failure without file names or header values. */
public final class ImportRecognitionException extends IocExtractorException {

    /** Stable recognition result category. */
    public enum Reason {
        SOURCE_NOT_CONFIGURED,
        CONTRACT_NOT_RECOGNIZED,
        CONTRACT_AMBIGUOUS
    }

    private final Reason reason;

    /** Creates a safe critical recognition failure. */
    public ImportRecognitionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** Returns the stable recognition reason. */
    public Reason reason() {
        return reason;
    }
}
