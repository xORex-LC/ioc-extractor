package com.iocextractor.adapter.out.sink.csv;

import java.util.Objects;

/**
 * Expected data-dependent inability of a mapping component to produce one cell.
 *
 * <p>This exception is part of the provider/transform SPI. It must be used only
 * when the current input value cannot be mapped while the component and its
 * configuration remain valid. The message and optional cause must be safe for
 * operator-facing diagnostics and must not contain the raw input value.
 */
public final class MappingValueException extends RuntimeException {

    /** Creates an expected mapping rejection with an operator-safe reason. */
    public MappingValueException(String message) {
        super(requireReason(message));
    }

    /** Creates an expected mapping rejection with an operator-safe cause. */
    public MappingValueException(String message, Throwable cause) {
        super(requireReason(message), Objects.requireNonNull(cause, "cause"));
    }

    private static String requireReason(String message) {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }
}
