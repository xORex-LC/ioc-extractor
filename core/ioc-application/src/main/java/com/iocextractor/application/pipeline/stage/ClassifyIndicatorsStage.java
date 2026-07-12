package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicators;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.Objects;

/** Materializes one reusable classification decision per attributed indicator. */
public final class ClassifyIndicatorsStage implements Stage<AttributedIndicators, ClassifiedIndicators> {

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
    public Envelope<ClassifiedIndicators> process(Envelope<AttributedIndicators> input) {
        var classified = input.payload().indicators().stream()
                .map(indicator -> new ClassifiedIndicator(indicator, matchPolicy.classify(indicator)))
                .toList();
        return input.withPayload(new ClassifiedIndicators(classified));
    }
}
