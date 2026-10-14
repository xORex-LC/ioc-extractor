package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.diagnostics.result.Result;
import java.util.List;

/** One document's preparation scope; close releases invocation-owned semantic state. */
public interface DocumentProcessingSession extends AutoCloseable {
    /** Processes every occurrence independently, including its candidates and diagnostics. */
    Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence);

    @Override
    void close();
}
