package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.model.Indicator;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Attributes extracted indicators with source context. Indicators that no
 * section marker preceded keep an empty {@code source}; when any are present the
 * stage emits a {@code WARN} diagnostic so unmatched markers are not silent.
 */
public final class AttributeSourceStage implements Stage<ExtractedIndicators, AttributedIndicators> {

    private final SourceAttributor attributor;
    private final Clock clock;
    private final PipelineDecisionTracer tracer;

    /**
     * Creates the stage.
     *
     * @param attributor source attribution service
     * @param clock clock for diagnostic timestamps
     * @param tracer gated operational decision boundary
     */
    public AttributeSourceStage(SourceAttributor attributor, Clock clock, PipelineDecisionTracer tracer) {
        this.attributor = Objects.requireNonNull(attributor, "attributor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public StageId name() {
        return StageNames.ATTRIBUTE;
    }

    @Override
    public Envelope<AttributedIndicators> process(Envelope<ExtractedIndicators> input) {
        var payload = input.payload();
        var outcome = attributor.attribute(payload.text(), payload.rawIndicators());
        if (tracer.isEnabled()) {
            outcome.decisions().forEach(decision -> {
                var raw = decision.rawIndicator();
                tracer.trace(PipelineItemDecision.builder(
                                PipelineDecisionKind.ATTRIBUTION,
                                decision.marker().isPresent() ? "attributed" : "unattributed")
                        .item(raw.type().name(), raw.value())
                        .rule(decision.marker().map(marker -> marker.label()).orElse("none"))
                        .span(raw.position(), raw.position() + raw.value().length())
                        .build());
            });
        }
        List<Indicator> attributed = outcome.indicators();
        Envelope<AttributedIndicators> output = input.withPayload(new AttributedIndicators(outcome));

        long unattributed = attributed.stream().filter(i -> i.source().label() == null).count();
        if (unattributed > 0) {
            output = output.withDiagnostic(Diagnostic.builder(SourceDiagnosticCodes.MARKERS_UNMATCHED, clock)
                    .with("unattributed", unattributed)
                    .with("total", attributed.size())
                    .build());
        }
        return output;
    }
}
