package com.iocextractor.application.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only source identity for idempotent fetch: path + size + modification timestamp.
 */
public record RemoteObjectIdentity(String path, long size, Instant modifiedAt) {

    public RemoteObjectIdentity {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt");
    }
}
