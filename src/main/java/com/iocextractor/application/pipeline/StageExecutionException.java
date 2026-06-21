package com.iocextractor.application.pipeline;

import com.iocextractor.common.IocExtractorException;

/**
 * Unchecked exception for invalid pipeline execution state.
 */
public class StageExecutionException extends IocExtractorException {

    /**
     * Creates an exception with message.
     *
     * @param message explanation
     */
    public StageExecutionException(String message) {
        super(message);
    }

    /**
     * Creates an exception with message and cause.
     *
     * @param message explanation
     * @param cause cause
     */
    public StageExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
