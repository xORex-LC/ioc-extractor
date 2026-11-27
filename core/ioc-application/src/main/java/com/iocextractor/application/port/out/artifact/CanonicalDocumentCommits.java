package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.DocumentArtifactCommit;
import com.iocextractor.application.artifact.DocumentWriteIdentity;

import java.util.Map;

/** Inspects canonical transaction evidence for a durable document plan. */
@FunctionalInterface
public interface CanonicalDocumentCommits {

    /**
     * Reads all committed artifacts in one snapshot, including no-op outcomes.
     * An empty map means this plan's verified observation has committed no artifacts.
     * Conflicting registration, source, mode or plan evidence fails closed.
     *
     * @param identity admitted observation and frozen plan to verify
     * @return committed outcomes keyed by artifact name
     */
    Map<String, DocumentArtifactCommit> find(DocumentWriteIdentity identity);
}
