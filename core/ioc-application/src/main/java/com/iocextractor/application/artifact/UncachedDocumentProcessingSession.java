package com.iocextractor.application.artifact;

import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.port.out.artifact.DocumentProcessingSession;
import com.iocextractor.diagnostics.result.Result;
import java.util.List;
import java.util.Objects;

/** Stateless delegation for plans without invocation-owned semantic state. */
public final class UncachedDocumentProcessingSession implements DocumentProcessingSession {
    private final DocumentProcessingPlan plan;

    public UncachedDocumentProcessingSession(DocumentProcessingPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    @Override
    public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
        return plan.prepare(occurrence);
    }

    @Override
    public void close() {
        // The forwarding plan owns no per-document resources.
    }
}
