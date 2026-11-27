package com.iocextractor.application.artifact;

import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;

import java.util.Objects;

/**
 * Durable outcome of one verified document artifact transaction, including a no-op.
 *
 * @param inserted new public rows, including restarted lifecycles
 * @param publicRowsUpdated existing rows with changed public values
 * @param metadataOnlyRows rows with changed ordering provenance and unchanged public values
 * @param revision public artifact revision after the transaction
 * @param generation mutable projection generation requested or observed by the transaction
 */
public record DocumentArtifactCommit(int inserted, int publicRowsUpdated, int metadataOnlyRows,
                                     long revision, ProjectionGeneration generation) {

    public DocumentArtifactCommit {
        CanonicalWriteResult.validate(inserted, publicRowsUpdated, metadataOnlyRows, revision);
        Objects.requireNonNull(generation, "generation");
    }

    /** Returns the number of rows whose public values changed or were newly installed. */
    public int publicRowsChanged() {
        return Math.addExact(inserted, publicRowsUpdated);
    }

    /** Returns the compatibility writer's original counts without reserving IDs again. */
    public CanonicalWriteResult compatibilityResult() {
        return new CanonicalWriteResult(inserted, publicRowsUpdated, metadataOnlyRows, revision);
    }
}
