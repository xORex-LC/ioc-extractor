package com.iocextractor.application.port.in.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionConvergenceResult;

/** Driving port for durable mutable-dataframe convergence. */
@FunctionalInterface
public interface ConvergeArtifactProjectionsUseCase {

    /** Projects every currently pending configured artifact at most once in this pass. */
    ArtifactProjectionConvergenceResult convergePending();
}
