package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.DeduplicatedIndicators;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ClassificationDiagnosticCodes;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Materializes one reusable classification decision per retained indicator. */
public final class ClassifyIndicatorsStage implements Stage<DeduplicatedIndicators, RetainedIndicators> {

    private final MatchPolicy matchPolicy;
    private final DiagnosticFactory diagnosticFactory;

    /** Creates the classification stage. */
    public ClassifyIndicatorsStage(MatchPolicy matchPolicy, DiagnosticFactory diagnosticFactory) {
        this.matchPolicy = Objects.requireNonNull(matchPolicy, "matchPolicy");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
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
            if (!isSupported(indicator)) {
                diagnostics.add(unsupported(indicator));
                continue;
            }
            classified.add(new ClassifiedIndicator(indicator, classify(indicator)));
        }
        return input.withPayload(new RetainedIndicators(input.payload().extracted(), classified))
                .withDiagnostics(diagnostics);
    }

    private ClassificationDecision classify(Indicator indicator) {
        if (indicator.type().category() == IndicatorCategory.NETWORK) {
            return matchPolicy.classify(indicator);
        }
        var neutralFeatures = new IndicatorFeatures(
                indicator.value(), indicator.value(), false, false, false, HostKind.UNKNOWN);
        return new ClassificationDecision(neutralFeatures, -1, List.of(), new MaskMatch(null, null));
    }

    private boolean isSupported(Indicator indicator) {
        return indicator.type().category() == IndicatorCategory.NETWORK
                || indicator.type().category() == IndicatorCategory.FILE;
    }

    private Diagnostic unsupported(Indicator indicator) {
        return diagnosticFactory.create(ClassificationDiagnosticCodes.UNSUPPORTED_INDICATOR_TYPE)
                .with(DiagnosticContextKeys.TYPE, indicator.type())
                .with("classifier", matchPolicy.getClass().getSimpleName())
                .with(DiagnosticContextKeys.INDICATOR, indicator.value())
                .build();
    }
}
