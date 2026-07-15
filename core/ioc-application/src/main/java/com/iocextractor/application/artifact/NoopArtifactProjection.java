package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;

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
    public ArtifactProjectionResult project(ArtifactProjectionCommand request) {
        Objects.requireNonNull(request, "request");
        return ArtifactProjectionResult.clean(0);
    }
}
