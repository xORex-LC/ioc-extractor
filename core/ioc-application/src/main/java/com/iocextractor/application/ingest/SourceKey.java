package com.iocextractor.application.ingest;

import java.util.Objects;

/**
 * Stable idempotency key for a source file. Stage 10 uses the content hash as
 * the key, so a repeated file is detected regardless of its original name.
 *
 * @param value lowercase hash value
 */
public record SourceKey(String value) {

    public SourceKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("source key must not be blank");
        }
        value = value.toLowerCase();
    }
}
