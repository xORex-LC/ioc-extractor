package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.DeduplicatedIndicators;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
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

import java.util.List;
import java.util.Objects;

/** Materializes one reusable classification decision per retained indicator. */
public final class ClassifyIndicatorsStage implements Stage<DeduplicatedIndicators, RetainedIndicators> {

    private final MatchPolicy matchPolicy;

    /** Creates the classification stage. */
    public ClassifyIndicatorsStage(MatchPolicy matchPolicy) {
        this.matchPolicy = Objects.requireNonNull(matchPolicy, "matchPolicy");
    }

    @Override
    public StageId name() {
        return StageNames.CLASSIFY;
    }

    @Override
    public Envelope<RetainedIndicators> process(Envelope<DeduplicatedIndicators> input) {
        var classified = input.payload().retained().stream()
                .map(indicator -> new ClassifiedIndicator(indicator, classify(indicator)))
                .toList();
        return input.withPayload(new RetainedIndicators(input.payload().extracted(), classified));
    }

    private ClassificationDecision classify(Indicator indicator) {
        if (indicator.type().category() == IndicatorCategory.NETWORK) {
            return matchPolicy.classify(indicator);
        }
        var neutralFeatures = new IndicatorFeatures(
                indicator.value(), indicator.value(), false, false, false, HostKind.UNKNOWN);
        return new ClassificationDecision(neutralFeatures, -1, List.of(), new MaskMatch(null, null));
    }
}
