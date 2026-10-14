package com.iocextractor.application.pipeline.payload;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Final pipeline payload with extraction and artifact-write counters.
 *
 * @param extracted number of extracted indicators
 * @param retained number of retained indicators
 * @param writtenPerArtifact rows written per artifact
 */
public record ArtifactWriteSummary(int extracted,
                                   int retained,
                                   Map<String, Integer> writtenPerArtifact,
                                   Set<String> changedArtifacts) {

    public ArtifactWriteSummary {
        writtenPerArtifact = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(writtenPerArtifact, "writtenPerArtifact")));
        changedArtifacts = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(changedArtifacts, "changedArtifacts")));
    }

    public ArtifactWriteSummary(int extracted, int retained, Map<String, Integer> writtenPerArtifact) {
        this(extracted, retained, writtenPerArtifact,
                writtenPerArtifact.entrySet().stream()
                        .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }
}
