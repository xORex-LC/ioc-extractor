package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.artifact.UncachedDocumentProcessingSession;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.diagnostics.result.Result;
import java.util.List;
import com.iocextractor.application.artifact.DocumentObservationSelection;

/** Executes one admitted document observation without owning identity or durable writes. */
@FunctionalInterface
public interface DocumentProcessingPlan {
    /** Final-key selection is appropriate for plans that collapse derived views. */
    default DocumentObservationSelection observationSelection() {
        return DocumentObservationSelection.FINAL_KEY;
    }

    /** Returns zero or more selected branch candidates and final expected diagnostics. */
    Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence);

    /** Opens an uncached scope by default, preserving existing functional implementations. */
    default DocumentProcessingSession openSession() {
        return new UncachedDocumentProcessingSession(this);
    }
}
