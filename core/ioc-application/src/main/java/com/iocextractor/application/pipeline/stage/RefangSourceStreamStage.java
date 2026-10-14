package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

/** Applies ordered literal passes without retaining whole source strings. */
public record RefangSourceStreamStage(Refanger refanger, PipelineDecisionTracer tracer)
        implements Stage<DocumentSourceWorkspace, DocumentSourceWorkspace> {
    public StageId name() { return StageNames.REFANG; }
    public Envelope<DocumentSourceWorkspace> process(Envelope<DocumentSourceWorkspace> input) {
        var decisions = refanger.refang(input.payload());
        if (tracer.isEnabled()) {
            for (var decision : decisions) {
                tracer.trace(PipelineItemDecision.builder(PipelineDecisionKind.REFANG, "replaced")
                        .identity("refang-rule:" + decision.ruleIndex())
                        .rule(decision.rule().from() + " -> " + decision.rule().to())
                        .result("replacements=" + decision.replacements()).build());
            }
        }
        return input;
    }
}
