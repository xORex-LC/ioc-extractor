package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.ArtifactPreparationBatch;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.diagnostics.result.Result;

import java.util.List;
import java.util.Objects;

/** Driven port for side-effect-free artifact filtering and row preparation. */
public interface ArtifactPreparer {

    /** Stable artifact name used in summaries and run ledgers. */
    String name();

    /**
     * Prepares accepted rows without reserving ids or performing durable I/O.
     * Element data failures are returned as diagnostics while valid rows remain in the plan.
     */
    Result<ArtifactWritePlan> prepare(List<ClassifiedIndicator> indicators);

    /**
     * Prepares from the occurrence-preserving pipeline view. Legacy preparers
     * retain their prior unique-IOC input unless they explicitly opt in.
     */
    default Result<ArtifactWritePlan> prepare(ArtifactPreparationBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return prepare(batch.retained());
    }
}
