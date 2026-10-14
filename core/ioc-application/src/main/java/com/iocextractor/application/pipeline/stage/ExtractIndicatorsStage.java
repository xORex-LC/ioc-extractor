package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.result.BoundedDiagnosticCollector;
import com.iocextractor.diagnostics.result.DiagnosticBatch;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.IndicatorExtractor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extracts raw indicators from refanged text.
 */
public final class ExtractIndicatorsStage implements Stage<RefangedText, ExtractedIndicators> {

    private final IndicatorExtractor extractor;
    private final DiagnosticFactory diagnosticFactory;
    private final PipelineDecisionTracer tracer;
    private final int diagnosticLimit;

    /**
     * Creates the stage.
     *
     * @param extractor indicator extractor
     * @param diagnosticFactory factory for element extraction diagnostics
     * @param tracer gated operational decision boundary
     */
    public ExtractIndicatorsStage(IndicatorExtractor extractor,
                                  DiagnosticFactory diagnosticFactory,
                                  PipelineDecisionTracer tracer) {
        this(extractor, diagnosticFactory, tracer, 10_000);
    }

    /** Creates the stage with the same retained-occurrence budget as its runner. */
    public ExtractIndicatorsStage(IndicatorExtractor extractor,
                                  DiagnosticFactory diagnosticFactory,
                                  PipelineDecisionTracer tracer, int diagnosticLimit) {
        if (diagnosticLimit < 1) {
            throw new IllegalArgumentException("diagnosticLimit must be positive");
        }
        this.diagnosticLimit = diagnosticLimit;
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public StageId name() {
        return StageNames.EXTRACT;
    }

    @Override
    public Envelope<ExtractedIndicators> process(Envelope<RefangedText> input) {
        var text = input.payload().text();
        var outcome = extractor.extract(text);
        trace(outcome.decisions());
        return input.withPayload(new ExtractedIndicators(text, outcome))
                .withDiagnostics(diagnostics(outcome.decisions()));
    }

    private void trace(List<ExtractionDecision> decisions) {
        if (!tracer.isEnabled()) {
            return;
        }
        decisions.forEach(decision -> ExtractionDecisionObserver.trace(decision, tracer));
    }

    private DiagnosticBatch diagnostics(List<ExtractionDecision> decisions) {
        var diagnostics = new BoundedDiagnosticCollector(diagnosticLimit);
        Map<SpanKey, ExtractionDecision> acceptedBySpan = new HashMap<>();
        for (ExtractionDecision decision : decisions) {
            if (decision.status() == ExtractionDecisionStatus.ACCEPTED) {
                acceptedBySpan.put(SpanKey.from(decision), decision);
            }
        }
        for (ExtractionDecision decision : decisions) {
            if (decision.status() != ExtractionDecisionStatus.DROPPED_OVERLAP) {
                continue;
            }
            ExtractionDecision accepted = acceptedBySpan.get(SpanKey.from(decision));
            diagnostics.add(ExtractionDecisionObserver.dropped(decision,
                    accepted == null ? null : accepted.type(), diagnosticFactory));
        }
        return diagnostics.batch();
    }

    private record SpanKey(int start, int end) {

        private static SpanKey from(ExtractionDecision decision) {
            return new SpanKey(decision.span().start(), decision.span().end());
        }
    }
}
