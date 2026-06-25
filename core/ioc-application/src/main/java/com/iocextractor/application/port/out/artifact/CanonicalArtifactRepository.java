package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.CanonicalArtifact;

/**
 * Driven port for canonical artifact storage.
 */
public interface CanonicalArtifactRepository {

    CanonicalArtifact load(String artifactName);

    void write(String artifactName, CanonicalArtifact artifact);
}
