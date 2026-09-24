package com.iocextractor.application.artifact;

/**
 * Outcome of one atomic canonical artifact write.
 *
 * @param inserted number of newly inserted public artifact rows
 * @param publicRowsUpdated existing rows whose public values changed
 * @param metadataOnlyRows rows whose ordering origin advanced without a public change
 * @param revision canonical artifact revision observed after the write
 */
public record CanonicalWriteResult(int inserted,
                                   int publicRowsUpdated,
                                   int metadataOnlyRows,
                                   long revision) {

    public CanonicalWriteResult {
        if (inserted < 0 || publicRowsUpdated < 0 || metadataOnlyRows < 0) {
            throw new IllegalArgumentException("Canonical write counts must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        if (Math.addExact(inserted, publicRowsUpdated) > 0 && revision == 0) {
            throw new IllegalArgumentException("A mutating canonical write requires a positive revision");
        }
    }

    public CanonicalWriteResult(int inserted, long revision) {
        this(inserted, 0, 0, revision);
    }

    /** Number of rows whose public representation changed. */
    public int publicRowsChanged() {
        return Math.addExact(inserted, publicRowsUpdated);
    }
}
