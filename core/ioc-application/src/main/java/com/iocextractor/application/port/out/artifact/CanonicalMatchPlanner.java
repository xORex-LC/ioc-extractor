package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.CanonicalMatchPlan;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;

import java.util.List;

/** Driven port for snapshot-consistent active canonical matching. */
public interface CanonicalMatchPlanner {

    /** Plans zero/one/multiple candidates for every request without mutating canonical state. */
    List<CanonicalMatchPlan> plan(String artifactName,
                                  EffectiveTime asOf,
                                  List<CanonicalMatchRequest> requests);
}
