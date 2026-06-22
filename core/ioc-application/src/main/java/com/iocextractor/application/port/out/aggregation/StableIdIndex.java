package com.iocextractor.application.port.out.aggregation;

import com.iocextractor.application.aggregation.ArtifactRowKey;
import com.iocextractor.application.aggregation.StableArtifactId;

/**
 * Driven port for stable ids assigned per artifact row identity.
 */
public interface StableIdIndex {

    StableArtifactId getOrCreate(String artifactName, ArtifactRowKey key);

    void save();
}
