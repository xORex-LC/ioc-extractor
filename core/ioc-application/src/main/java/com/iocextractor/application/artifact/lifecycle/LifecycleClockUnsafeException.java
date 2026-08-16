package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.common.IocExtractorException;

/** Signals that effective lifecycle time can no longer be established safely. */
public final class LifecycleClockUnsafeException extends IocExtractorException {

    public LifecycleClockUnsafeException(String message) {
        super(message);
    }
}
