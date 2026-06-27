package com.iocextractor.application.artifact;

/**
 * Outcome of one atomic canonical artifact write.
 *
 * @param inserted number of newly inserted public artifact rows
 * @param revision canonical artifact revision observed after the write
 */
public record CanonicalWriteResult(int inserted, long revision) {

    public CanonicalWriteResult {
        if (inserted < 0) {
            throw new IllegalArgumentException("Inserted row count must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        if (inserted > 0 && revision == 0) {
            throw new IllegalArgumentException("A mutating canonical write requires a positive revision");
        }
    }
}
