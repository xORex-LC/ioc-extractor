package com.iocextractor.application.port.out.aggregation;

import com.iocextractor.application.aggregation.CanonicalArtifact;

/**
 * Driven port for canonical artifact storage.
 */
public interface CanonicalArtifactRepository {

    CanonicalArtifact load(String artifactName);

    void write(String artifactName, CanonicalArtifact artifact);
}
