package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.attribute.SourceMarker;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import java.time.Clock;
import java.util.Optional;

/** Attributes ordered accepted occurrences with one advancing bounded marker cursor. */
public record AttributeSourceStreamStage(SourceAttributor attributor, Clock clock, PipelineDecisionTracer tracer)
        implements Stage<DocumentSourceWorkspace, AttributedIndicators> {
    public StageId name() { return StageNames.ATTRIBUTE; }
    public Envelope<AttributedIndicators> process(Envelope<DocumentSourceWorkspace> input) {
        var source = input.payload();
        var markers = attributor.markers(source.text(), source.maximumMatchCharacters());
        SourceMarker preceding = null;
        SourceMarker next = markers.next() ? markers.value() : null;
        long unattributed = 0;
        var indicators = source.indicators();
        try (var cursor = indicators.open()) {
            while (cursor.next()) {
                var raw = cursor.value();
                while (next != null && next.position() <= raw.position()) {
                    preceding = next;
                    next = markers.next() ? markers.value() : null;
                }
                var decision = new AttributionDecision(raw, Optional.ofNullable(preceding));
                source.attribute(decision);
                if (preceding == null) { unattributed++; }
                trace(decision);
            }
        }
        // Validate the remainder too; no indicator near EOF is needed to trigger a marker limit.
        while (markers.next()) { markers.value(); }
        var output = input.withPayload(new AttributedIndicators(source.attributions()));
        if (unattributed > 0) { output = output.withDiagnostic(Diagnostic.builder(SourceDiagnosticCodes.MARKERS_UNMATCHED, clock)
                .with("unattributed", unattributed).with("total", indicators.size()).build()); }
        return output;
    }
    private void trace(AttributionDecision decision) {
        if (!tracer.isEnabled()) { return; }
        var raw = decision.rawIndicator();
        var marker = decision.marker().orElse(null);
        tracer.trace(PipelineItemDecision.builder(PipelineDecisionKind.ATTRIBUTION,
                        marker == null ? "unattributed" : "attributed")
                .item(raw.type().name(), raw.value()).rule(marker == null ? "none" : marker.label())
                .span(raw.position(), raw.position() + raw.value().length()).build());
    }
}
