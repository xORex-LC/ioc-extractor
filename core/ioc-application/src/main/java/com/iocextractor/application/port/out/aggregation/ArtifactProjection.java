package com.iocextractor.application.port.out.aggregation;

/**
 * Derived artifact projection written after canonical storage commits.
 */
public interface ArtifactProjection {

    /**
     * Refreshes one derived artifact from canonical truth.
     *
     * @param artifactName artifact to project
     */
    void project(String artifactName);
}
