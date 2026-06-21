package com.iocextractor.application.port.in;

import java.util.Map;

/**
 * Outcome summary of an extraction run.
 *
 * @param extracted        indicators detected (after overlap resolution)
 * @param retained         indicators kept after de-duplication
 * @param writtenPerArtifact rows written, keyed by artifact name
 */
public record ExtractionResult(int extracted, int retained, Map<String, Integer> writtenPerArtifact) {
}
