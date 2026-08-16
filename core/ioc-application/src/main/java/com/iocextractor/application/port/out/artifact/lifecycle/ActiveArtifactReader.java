package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.ActiveArtifactSnapshot;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;

/** Driven read port that exposes only canonical records active at an explicit boundary. */
public interface ActiveArtifactReader {

    /**
     * Reads one artifact snapshot using the strict {@code validUntil > asOf} predicate.
     *
     * @param artifactName configured artifact
     * @param asOf one effective time shared by the full read
     * @return active-only snapshot and observed revision/generation
     */
    ActiveArtifactSnapshot loadActive(String artifactName, EffectiveTime asOf);
}
