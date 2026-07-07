package com.iocextractor.application.port.out.artifact;

/**
 * Driven port for reading the current public artifact id floor.
 *
 * <p>The extraction pipeline uses this only to continue configured id sequences
 * from canonical storage. It deliberately does not expose indicator lookup or
 * storage-level deduplication policy.
 */
public interface ArtifactIdBaseline {

    /**
     * Highest public id currently present for the named artifact, or {@code 0}
     * when the artifact has no public id column or contains no rows.
     *
     * @param artifactName artifact configured under {@code ioc.sink.artifacts}
     * @return current maximum public id for that artifact
     */
    long maxId(String artifactName);
}
