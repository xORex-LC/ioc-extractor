package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;

/**
 * Driven port for canonical artifact storage.
 */
public interface CanonicalArtifactRepository {

    CanonicalArtifact load(String artifactName);

    /**
     * Atomically writes new canonical rows and advances the artifact revision
     * exactly once when at least one public row was inserted.
     */
    CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact);
}
