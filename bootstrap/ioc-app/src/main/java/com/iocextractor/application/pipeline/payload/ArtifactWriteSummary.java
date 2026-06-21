package com.iocextractor.application.pipeline.payload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Final pipeline payload with extraction and artifact-write counters.
 *
 * @param extracted number of extracted indicators
 * @param retained number of retained indicators
 * @param writtenPerArtifact rows written per artifact
 */
public record ArtifactWriteSummary(int extracted, int retained, Map<String, Integer> writtenPerArtifact) {

    public ArtifactWriteSummary {
        writtenPerArtifact = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(writtenPerArtifact, "writtenPerArtifact")));
    }
}
