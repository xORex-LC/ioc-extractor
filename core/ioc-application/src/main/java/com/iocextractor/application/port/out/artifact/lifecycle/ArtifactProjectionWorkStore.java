package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionState;
import com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;

/** Driven port for durable mutable-artifact projection convergence. */
public interface ArtifactProjectionWorkStore {

    /** Returns the current required/projected generations for one artifact. */
    ArtifactProjectionState load(String artifactName);

    /**
     * Acknowledges only the generation represented by the installed file.
     *
     * <p>The operation returns {@code false} when newer required work appeared
     * after the caller read state, leaving convergence pending.
     *
     * @param acknowledgement compare-and-set projection evidence
     * @return whether the durable state accepted the acknowledgement
     */
    boolean acknowledge(ProjectionAcknowledgement acknowledgement);

    /**
     * Records a stable aggregate failure code only while the observed
     * generation is still current.
     *
     * @return whether the failure was attached to the expected generation
     */
    boolean recordFailure(String artifactName,
                          ProjectionGeneration expectedGeneration,
                          String failureCode);
}
