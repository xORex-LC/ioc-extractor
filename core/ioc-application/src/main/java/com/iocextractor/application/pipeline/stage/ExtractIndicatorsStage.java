package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExtractionDiagnosticCodes;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.IndicatorExtractor;

import java.util.ArrayList;
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

    /**
     * Creates the stage.
     *
     * @param extractor indicator extractor
     * @param diagnosticFactory factory for element extraction diagnostics
     */
    public ExtractIndicatorsStage(IndicatorExtractor extractor, DiagnosticFactory diagnosticFactory) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    @Override
    public StageId name() {
        return StageNames.EXTRACT;
    }

    @Override
    public Envelope<ExtractedIndicators> process(Envelope<RefangedText> input) {
        var text = input.payload().text();
        var outcome = extractor.extract(text);
        return input.withPayload(new ExtractedIndicators(text, outcome))
                .withDiagnostics(diagnostics(outcome.decisions()));
    }

    private List<Diagnostic> diagnostics(List<ExtractionDecision> decisions) {
        var diagnostics = new ArrayList<Diagnostic>();
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
            if (accepted != null) {
                diagnostics.add(diagnosticFactory.create(ExtractionDiagnosticCodes.AMBIGUOUS_VALUE)
                        .with(DiagnosticContextKeys.VALUE, decision.span().value())
                        .with(DiagnosticContextKeys.TYPE, decision.type())
                        .with("spanStart", decision.span().start())
                        .with("spanEnd", decision.span().end())
                        .with("reason", "also matched higher-priority type " + accepted.type())
                        .build());
                continue;
            }
            diagnostics.add(diagnosticFactory.create(ExtractionDiagnosticCodes.INDICATOR_SKIPPED)
                    .with(DiagnosticContextKeys.INDICATOR, decision.span().value())
                    .with(DiagnosticContextKeys.TYPE, decision.type())
                    .with("pattern", decision.pattern())
                    .with("spanStart", decision.span().start())
                    .with("spanEnd", decision.span().end())
                    .with("reason", "overlaps a higher-priority match")
                    .build());
        }
        return diagnostics;
    }

    private record SpanKey(int start, int end) {

        private static SpanKey from(ExtractionDecision decision) {
            return new SpanKey(decision.span().start(), decision.span().end());
        }
    }
}
