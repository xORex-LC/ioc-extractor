package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.common.IocExtractorException;

/** Strict parser-boundary failure safe to classify during exact-one recognition. */
public class DelimitedInputReadException extends IocExtractorException {

    /** Creates a safe structural or resource-limit failure. */
    public DelimitedInputReadException(String message) {
        super(message);
    }

    /** Creates a safe decoder or I/O failure with its cause retained. */
    public DelimitedInputReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
