package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.application.pipeline.payload.SourceText;
import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.domain.refang.Refanger;

import java.util.Objects;

/**
 * Applies configured refang replacements to source text.
 */
public final class RefangStage implements Stage<SourceText, RefangedText> {

    private final Refanger refanger;
    private final PipelineDecisionTracer tracer;

    /**
     * Creates the stage.
     *
     * @param refanger refang service
     * @param tracer gated operational decision boundary
     */
    public RefangStage(Refanger refanger, PipelineDecisionTracer tracer) {
        this.refanger = Objects.requireNonNull(refanger, "refanger");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public StageId name() {
        return StageNames.REFANG;
    }

    @Override
    public Envelope<RefangedText> process(Envelope<SourceText> input) {
        var outcome = refanger.refang(input.payload().text());
        if (tracer.isEnabled()) {
            outcome.decisions().forEach(decision -> tracer.trace(PipelineItemDecision
                    .builder(PipelineDecisionKind.REFANG, "replaced")
                    .identity("refang-rule:" + decision.ruleIndex())
                    .rule(decision.rule().from() + " -> " + decision.rule().to())
                    .result("replacements=" + decision.replacements())
                    .build()));
        }
        return input.withPayload(new RefangedText(outcome));
    }
}
