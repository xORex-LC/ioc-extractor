package com.iocextractor.adapter.out.sink.csv;

/** Internal unchecked bridge for I/O failures raised from row-consumer callbacks. */
final class SliceWriteException extends RuntimeException {

    SliceWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
