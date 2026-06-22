package com.iocextractor.application.aggregation;

/**
 * Stable id allocated for an artifact row key.
 *
 * @param value numeric id
 * @param newlyCreated true when the id was allocated during this operation
 */
public record StableArtifactId(long value, boolean newlyCreated) {
}
