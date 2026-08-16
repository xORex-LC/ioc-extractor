package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdStrategy;

import java.util.Objects;

/** Configuration identity used to initialize or validate one public-id allocator. */
record ArtifactIdAllocatorDefinition(String artifact,
                                     ArtifactIdStrategy strategy,
                                     long configuredNextValue,
                                     long identityEpoch) {

    ArtifactIdAllocatorDefinition {
        artifact = DataframeColumn.requireSqlIdentifier(artifact, "artifact name");
        Objects.requireNonNull(strategy, "strategy");
        if (identityEpoch <= 0) {
            throw new IllegalArgumentException("Identity epoch must be positive");
        }
    }
}
