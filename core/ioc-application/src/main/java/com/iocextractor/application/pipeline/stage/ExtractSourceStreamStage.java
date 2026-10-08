package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.result.BoundedDiagnosticCollector;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

/** Performs global priority selection with disk-owned decisions and overlap state. */
public record ExtractSourceStreamStage(IndicatorExtractor extractor, DiagnosticFactory diagnostics,
        PipelineDecisionTracer tracer, int diagnosticLimit)
        implements Stage<DocumentSourceWorkspace, DocumentSourceWorkspace> {
    public StageId name() { return StageNames.EXTRACT; }
    public Envelope<DocumentSourceWorkspace> process(Envelope<DocumentSourceWorkspace> input) {
        var source = input.payload();
        extractor.extract(source.text(), source.maximumMatchCharacters(), source);
        var collected = new BoundedDiagnosticCollector(diagnosticLimit);
        try (var cursor = source.decisions().open()) {
            while (cursor.next()) {
                var decision = cursor.value();
                ExtractionDecisionObserver.trace(decision, tracer);
                if (decision.status() == ExtractionDecisionStatus.DROPPED_OVERLAP) {
                    collected.add(ExtractionDecisionObserver.dropped(decision,
                            source.acceptedType(decision.span().start(), decision.span().end()).orElse(null), diagnostics));
                }
            }
        }
        return input.withDiagnostics(collected.batch());
    }
}
