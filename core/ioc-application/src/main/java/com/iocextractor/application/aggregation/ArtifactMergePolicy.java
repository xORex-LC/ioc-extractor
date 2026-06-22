package com.iocextractor.application.aggregation;

/**
 * Decides how a new partition row interacts with an existing canonical row for
 * the same artifact identity.
 */
public interface ArtifactMergePolicy {

    /**
     * Returns the row that should remain in the canonical artifact.
     *
     * @param existing existing canonical row
     * @param candidate new partition row
     * @return retained row
     */
    ArtifactRow merge(ArtifactRow existing, ArtifactRow candidate);
}
