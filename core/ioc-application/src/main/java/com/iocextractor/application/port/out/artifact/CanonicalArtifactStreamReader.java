package com.iocextractor.application.port.out.artifact;

/** Driven read port for bounded-memory canonical artifact projections. */
public interface CanonicalArtifactStreamReader {

    /**
     * Streams the current active snapshot in canonical ID order.
     *
     * <p>The callback is synchronous: implementations keep one consistent read
     * boundary open until it returns. Consumers must not retain rows or dispatch
     * callback work to another thread.</p>
     *
     * @param artifactName configured artifact name
     * @param consumer synchronous row callback
     * @return exact number of delivered rows
     */
    int stream(String artifactName, CanonicalArtifactRowConsumer consumer);
}
