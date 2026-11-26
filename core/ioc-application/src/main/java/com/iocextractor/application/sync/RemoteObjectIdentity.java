package com.iocextractor.application.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable fetch identity within one configured logical source.
 *
 * @param sourceId stable configured fetch source name
 * @param path full remote path returned by the transport
 * @param size listed size in bytes
 * @param modifiedAt listed modification timestamp
 */
public record RemoteObjectIdentity(String sourceId, String path, long size, Instant modifiedAt) {

    public RemoteObjectIdentity {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt");
    }
}
