package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.diagnostics.result.Result;
import java.util.List;

/** Executes one admitted document observation without owning identity or durable writes. */
@FunctionalInterface
public interface DocumentProcessingPlan {
    /** Returns zero or more selected branch candidates and final expected diagnostics. */
    Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence);
}
