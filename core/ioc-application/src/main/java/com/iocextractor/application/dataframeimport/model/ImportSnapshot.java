package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/**
 * Proven immutable local snapshot evidence.
 *
 * @param reference opaque private snapshot reference
 * @param digest digest computed during durable materialization
 * @param size exact byte size
 */
public record ImportSnapshot(
        ImportSnapshotReference reference,
        ImportSha256 digest,
        long size) {

    /** Enforces complete non-negative snapshot evidence. */
    public ImportSnapshot {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(digest, "digest");
        if (size < 0) {
            throw new IllegalArgumentException("Import snapshot size must not be negative");
        }
    }
}
