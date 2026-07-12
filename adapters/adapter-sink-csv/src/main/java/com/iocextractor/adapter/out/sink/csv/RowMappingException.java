package com.iocextractor.adapter.out.sink.csv;

/** Typed data-dependent row mapping failure eligible for element-level continuation. */
public final class RowMappingException extends RuntimeException {

    /** Creates a typed data-mapping failure with an operator-safe reason. */
    public RowMappingException(String message) {
        super(message);
    }

    /** Creates a typed data-mapping failure with its underlying cause. */
    public RowMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
