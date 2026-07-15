package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionRequest;
import com.iocextractor.application.port.out.artifact.ProjectionOutcome;

import java.util.Objects;

/**
 * Projection implementation for modes where canonical storage has no derived CSV output.
 */
public final class NoopArtifactProjection implements ArtifactProjection {

    /** Shared stateless no-op projection. */
    public static final NoopArtifactProjection INSTANCE = new NoopArtifactProjection();

    private NoopArtifactProjection() {
    }

    @Override
    public ProjectionOutcome project(ArtifactProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        return ProjectionOutcome.clean(0);
    }
}
