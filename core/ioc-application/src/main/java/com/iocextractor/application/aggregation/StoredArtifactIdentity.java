package com.iocextractor.application.aggregation;

/**
 * Persisted identity formula marker for one artifact.
 */
public record StoredArtifactIdentity(String artifactName, String identityHash, int epoch) {
}
