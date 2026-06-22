package com.iocextractor.application.port.out.aggregation;

import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.ArtifactRowKey;

import java.util.Optional;

/**
 * Driven port for artifact-specific row identity extraction.
 */
public interface ArtifactIdentityResolver {

    /**
     * Resolves a stable row key.
     *
     * @param artifactName artifact name
     * @param row artifact row
     * @return stable key, or empty when the row cannot be identified
     */
    Optional<ArtifactRowKey> keyOf(String artifactName, ArtifactRow row);
}
