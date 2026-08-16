package com.iocextractor.application.artifact.lifecycle;

import java.util.List;
import java.util.Objects;

/** Aggregate outcome of one mutable-artifact convergence pass. */
public record ArtifactProjectionConvergenceResult(int projected,
                                                  int stillPending,
                                                  List<String> projectedArtifacts) {

    public ArtifactProjectionConvergenceResult {
        projectedArtifacts = List.copyOf(Objects.requireNonNull(projectedArtifacts, "projectedArtifacts"));
        if (projected < 0 || stillPending < 0 || projected != projectedArtifacts.size()) {
            throw new IllegalArgumentException("Projection convergence counters are inconsistent");
        }
    }
}
