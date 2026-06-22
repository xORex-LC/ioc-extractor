package com.iocextractor.application.aggregation;

import java.util.Objects;

/**
 * Stage 11 conflict policy: the first canonical row wins.
 */
public final class KeepFirstMergePolicy implements ArtifactMergePolicy {

    @Override
    public ArtifactRow merge(ArtifactRow existing, ArtifactRow candidate) {
        return Objects.requireNonNull(existing, "existing");
    }
}
