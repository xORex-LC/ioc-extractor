package com.iocextractor.application.port.out.dataframeimport;

/**
 * Driven port that atomically promotes one accepted delivery across every
 * affected dataframe artifact, lifecycle, alias, slot, revision and receipt.
 */
public interface CanonicalImportWriter {

    /**
     * Commits all accepted branches in one dataframe transaction or none.
     * Repeating the same delivery returns the persisted receipt without reapply.
     *
     * @param command sealed promotion request
     * @return new or replayed receipt summary
     */
    CanonicalImportResult promote(CanonicalImportCommand command);
}
