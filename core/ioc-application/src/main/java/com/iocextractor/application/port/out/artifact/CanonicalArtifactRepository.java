package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.CanonicalWriteCommand;

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

    /** Writes a compatibility-mode command with optional ordered fields. */
    default CanonicalWriteResult write(CanonicalWriteCommand command) {
        if (command.rows().stream().anyMatch(row -> !row.orderedFieldPositions().isEmpty())) {
            throw new UnsupportedOperationException("Canonical repository does not support ordered fields");
        }
        return write(command.artifactName(), command.artifact());
    }
}
