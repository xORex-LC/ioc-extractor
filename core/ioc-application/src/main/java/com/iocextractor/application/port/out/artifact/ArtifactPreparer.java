package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.diagnostics.result.Result;

import java.util.List;

/** Driven port for side-effect-free artifact filtering and row preparation. */
public interface ArtifactPreparer {

    /** Stable artifact name used in summaries and run ledgers. */
    String name();

    /**
     * Prepares accepted rows without reserving ids or performing durable I/O.
     * Element data failures are returned as diagnostics while valid rows remain in the plan.
     */
    Result<ArtifactWritePlan> prepare(List<ClassifiedIndicator> indicators);
}
