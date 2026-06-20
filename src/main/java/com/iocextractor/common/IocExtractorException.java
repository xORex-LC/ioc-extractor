package com.iocextractor.common;

/** Top-level unchecked exception for unrecoverable extraction/I-O failures. */
public class IocExtractorException extends RuntimeException {

    public IocExtractorException(String message, Throwable cause) {
        super(message, cause);
    }

    public IocExtractorException(String message) {
        super(message);
    }
}
