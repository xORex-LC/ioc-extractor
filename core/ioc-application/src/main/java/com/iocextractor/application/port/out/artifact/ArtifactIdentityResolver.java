package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;

import java.util.Optional;

/**
 * Driven port for artifact-specific row identity extraction.
 */
public interface ArtifactIdentityResolver {
    /** Full canonical material for spill grouping; production resolvers retain equality material. */
    default java.util.Optional<com.iocextractor.application.artifact.CanonicalKeyMaterial> materialOf(
            String artifactName, com.iocextractor.application.artifact.ArtifactRow row) {
        return keyOf(artifactName, row).map(key ->
                new com.iocextractor.application.artifact.CanonicalKeyMaterial("opaque", com.iocextractor.application.artifact.ArtifactIdentityDefinition.sha256(key.value()), key.value()));
    }

    /**
     * Resolves a stable row key.
     *
     * @param artifactName artifact name
     * @param row artifact row
     * @return stable key, or empty when the row cannot be identified
     */
    Optional<ArtifactRowKey> keyOf(String artifactName, ArtifactRow row);
}
