package com.iocextractor.application.pipeline.payload;

import com.iocextractor.application.artifact.ArtifactWritePlan;

import java.util.List;
import java.util.Objects;

/** Prepared write plans plus extraction counts carried across the policy checkpoint. */
public record PreparedArtifacts(int extracted,
                                int retained,
                                List<ArtifactWritePlan> plans) {

    public PreparedArtifacts {
        plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
    }
}
