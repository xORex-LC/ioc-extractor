package com.iocextractor.application.port.out.artifact;

/**
 * Mutable derived artifact projection written after canonical storage commits.
 * Production composition exposes the shared generation owner through this port.
 */
public interface ArtifactProjection {

    /**
     * Refreshes one derived artifact from canonical truth.
     * Tracked work returns after installation and durable coverage acknowledgement;
     * failures throw and leave pending work available to reconciliation.
     *
     * @param request projection operation identity
     * @return successfully installed projection outcome
     */
    ArtifactProjectionResult project(ArtifactProjectionCommand request);
}
