package com.iocextractor.application.port.out.artifact;

/**
 * Derived artifact projection written after canonical storage commits.
 */
public interface ArtifactProjection {

    /**
     * Refreshes one derived artifact from canonical truth.
     *
     * @param request projection operation identity
     * @return successfully installed projection outcome
     */
    ArtifactProjectionResult project(ArtifactProjectionCommand request);
}
