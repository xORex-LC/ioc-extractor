package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
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

    /**
     * Creates the stage.
     *
     * @param attributor source attribution service
     * @param clock clock for diagnostic timestamps
     */
    public AttributeSourceStage(SourceAttributor attributor, Clock clock) {
        this.attributor = Objects.requireNonNull(attributor, "attributor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StageId name() {
        return StageNames.ATTRIBUTE;
    }

    @Override
    public Envelope<AttributedIndicators> process(Envelope<ExtractedIndicators> input) {
        var payload = input.payload();
        List<Indicator> attributed = attributor.attribute(payload.text(), payload.rawIndicators());
        Envelope<AttributedIndicators> output = input.withPayload(new AttributedIndicators(attributed));

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
