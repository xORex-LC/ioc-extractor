package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;

/**
 * Projection implementation for modes where canonical storage has no derived CSV output.
 */
public final class NoopArtifactProjection implements ArtifactProjection {

    @Override
    public void project(String artifactName) {
    }
}
