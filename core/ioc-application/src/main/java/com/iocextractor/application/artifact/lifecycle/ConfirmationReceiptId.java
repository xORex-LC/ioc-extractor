package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/** Durable identity of one prepared-row confirmation receipt. */
public record ConfirmationReceiptId(String value) {

    /** Validates the opaque receipt identity. */
    public ConfirmationReceiptId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Confirmation receipt id must not be blank");
        }
    }
}
