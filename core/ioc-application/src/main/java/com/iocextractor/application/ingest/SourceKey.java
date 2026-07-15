package com.iocextractor.application.ingest;

import java.util.Objects;

/**
 * Stable idempotency key for a source file. Regular ingestion uses the content
 * hash so a repeated file is detected regardless of its original name. A
 * terminal failure before content can be read may use a metadata fingerprint
 * to durably suppress repeated handling of the same failed observation.
 *
 * @param value lowercase identity digest
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
