package com.iocextractor.application.artifact;

/** One active lifecycle candidate reached through one or more match aliases. */
public record CanonicalMatchCandidate(long canonicalRowId,
                                      long lifecycleId,
                                      ArtifactRowKey rowKey) {

    public CanonicalMatchCandidate {
        if (canonicalRowId <= 0 || lifecycleId <= 0) {
            throw new IllegalArgumentException("Canonical match identities must be positive");
        }
        if (rowKey == null) {
            throw new NullPointerException("rowKey");
        }
    }
}
