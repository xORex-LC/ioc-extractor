package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/**
 * Closed disk-backed staging evidence ready for canonical promotion.
 *
 * @param reference opaque adapter-owned stage reference
 * @param digest digest of sealed staging bytes
 * @param sourceRows parsed source row count
 * @param acceptedRows accepted logical row count
 * @param rejectedRows rejected logical row count
 */
public record ImportStage(
        ImportStageReference reference,
        ImportSha256 digest,
        long sourceRows,
        long acceptedRows,
        long rejectedRows) {

    /** Enforces complete staging evidence and non-negative counts. */
    public ImportStage {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(digest, "digest");
        if (sourceRows < 0 || acceptedRows < 0 || rejectedRows < 0) {
            throw new IllegalArgumentException("Import stage counts must not be negative");
        }
        if (acceptedRows > sourceRows || rejectedRows > sourceRows - acceptedRows) {
            throw new IllegalArgumentException("Logical import outcomes must not exceed source rows");
        }
    }
}
