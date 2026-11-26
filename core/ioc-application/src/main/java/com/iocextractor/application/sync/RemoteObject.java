package com.iocextractor.application.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata for one remote regular file.
 */
public record RemoteObject(String path, long size, Instant modifiedAt) {

    public RemoteObject {
        path = requireText(path, "path");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt");
    }

    /** Binds the transport metadata to the configured source for durable fetch identity. */
    public RemoteObjectIdentity identity(String sourceId) {
        return new RemoteObjectIdentity(sourceId, path, size, modifiedAt);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
