package com.iocextractor.application.artifact;

/**
 * Persisted identity formula marker for one artifact.
 */
public record StoredArtifactIdentity(String artifactName, String identityHash, int epoch) {
}
