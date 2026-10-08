package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExtractionDiagnosticCodes;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.model.IndicatorType;
import java.util.Locale;

/** Shared observation vocabulary for batch callers and cursor-based documents. */
final class ExtractionDecisionObserver {
    private ExtractionDecisionObserver() { }

    static void trace(ExtractionDecision decision, PipelineDecisionTracer tracer) {
        if (tracer.isEnabled()) {
            tracer.trace(PipelineItemDecision.builder(PipelineDecisionKind.EXTRACTION,
                            decision.status().name().toLowerCase(Locale.ROOT))
                    .item(decision.type().name(), decision.span().value()).pattern(decision.pattern())
                    .span(decision.span().start(), decision.span().end()).build());
        }
    }

    static Diagnostic dropped(ExtractionDecision decision, IndicatorType accepted, DiagnosticFactory factory) {
        if (accepted != null) {
            return factory.create(ExtractionDiagnosticCodes.AMBIGUOUS_VALUE)
                    .with(DiagnosticContextKeys.VALUE, decision.span().value())
                    .with(DiagnosticContextKeys.TYPE, decision.type())
                    .with("spanStart", decision.span().start()).with("spanEnd", decision.span().end())
                    .with("reason", "also matched higher-priority type " + accepted).build();
        }
        return factory.create(ExtractionDiagnosticCodes.INDICATOR_SKIPPED)
                .with(DiagnosticContextKeys.INDICATOR, decision.span().value())
                .with(DiagnosticContextKeys.TYPE, decision.type()).with("pattern", decision.pattern())
                .with("spanStart", decision.span().start()).with("spanEnd", decision.span().end())
                .with("reason", "overlaps a higher-priority match").build();
    }
}
