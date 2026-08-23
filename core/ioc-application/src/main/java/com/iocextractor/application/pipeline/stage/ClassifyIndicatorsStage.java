package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.classification.IndicatorClassifier;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.DeduplicatedIndicators;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ClassificationDiagnosticCodes;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.ArrayList;
import java.util.Objects;

/** Materializes one reusable classification decision per retained indicator. */
public final class ClassifyIndicatorsStage implements Stage<DeduplicatedIndicators, RetainedIndicators> {

    private final IndicatorClassifier classifier;
    private final DiagnosticFactory diagnosticFactory;
    private final PipelineDecisionTracer tracer;

    /** Creates the classification stage. */
    public ClassifyIndicatorsStage(MatchPolicy matchPolicy,
                                   DiagnosticFactory diagnosticFactory,
                                   PipelineDecisionTracer tracer) {
        this(new IndicatorClassifier(matchPolicy), diagnosticFactory, tracer);
    }

    /** Creates the classification stage with the reusable application classifier. */
    public ClassifyIndicatorsStage(IndicatorClassifier classifier,
                                   DiagnosticFactory diagnosticFactory,
                                   PipelineDecisionTracer tracer) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public StageId name() {
        return StageNames.CLASSIFY;
    }

    @Override
    public Envelope<RetainedIndicators> process(Envelope<DeduplicatedIndicators> input) {
        var classified = new ArrayList<ClassifiedIndicator>(input.payload().retained().size());
        var diagnostics = new ArrayList<Diagnostic>();
        for (Indicator indicator : input.payload().retained()) {
            if (!classifier.supports(indicator)) {
                diagnostics.add(unsupported(indicator));
                continue;
            }
            var decision = classifier.classify(indicator);
            classified.add(new ClassifiedIndicator(indicator, decision));
            trace(indicator, decision);
        }
        return input.withPayload(new RetainedIndicators(input.payload().extracted(), classified))
                .withDiagnostics(diagnostics);
    }

    private void trace(Indicator indicator, ClassificationDecision decision) {
        if (!tracer.isEnabled()) {
            return;
        }
        String outcome = indicator.type().category() == IndicatorCategory.FILE
                ? "not_applicable"
                : decision.matchedRuleIndex() >= 0 ? "matched" : "unmatched";
        var builder = PipelineItemDecision.builder(PipelineDecisionKind.CLASSIFICATION, outcome)
                .item(indicator.type().name(), indicator.value());
        if (decision.matchedRuleIndex() >= 0) {
            builder.rule(Integer.toString(decision.matchedRuleIndex()))
                    .pattern(String.join(",", decision.matchedPredicates()))
                    .result("url=" + decision.match().urlMatch() + ",host=" + decision.match().hostMatch());
        }
        tracer.trace(builder.build());
    }

    private Diagnostic unsupported(Indicator indicator) {
        return diagnosticFactory.create(ClassificationDiagnosticCodes.UNSUPPORTED_INDICATOR_TYPE)
                .with(DiagnosticContextKeys.TYPE, indicator.type())
                .with("classifier", classifier.name())
                .with(DiagnosticContextKeys.INDICATOR, indicator.value())
                .build();
    }
}
