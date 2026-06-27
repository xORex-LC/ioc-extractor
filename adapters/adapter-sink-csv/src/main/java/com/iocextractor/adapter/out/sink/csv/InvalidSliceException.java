package com.iocextractor.adapter.out.sink.csv;

/** Internal verification failure translated to an export diagnostic by the writer. */
final class InvalidSliceException extends RuntimeException {

    InvalidSliceException(String message) {
        super(message);
    }

    InvalidSliceException(String message, Throwable cause) {
        super(message, cause);
    }
}
