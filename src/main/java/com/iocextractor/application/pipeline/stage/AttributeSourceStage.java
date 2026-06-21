package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.Stage;
import com.iocextractor.application.pipeline.StageName;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.domain.attribute.SourceAttributor;

import java.util.Objects;

/**
 * Attributes extracted indicators with source context.
 */
public final class AttributeSourceStage implements Stage<ExtractedIndicators, AttributedIndicators> {

    private final SourceAttributor attributor;

    /**
     * Creates the stage.
     *
     * @param attributor source attribution service
     */
    public AttributeSourceStage(SourceAttributor attributor) {
        this.attributor = Objects.requireNonNull(attributor, "attributor");
    }

    @Override
    public StageName name() {
        return StageName.ATTRIBUTE;
    }

    @Override
    public Envelope<AttributedIndicators> process(Envelope<ExtractedIndicators> input) {
        var payload = input.payload();
        return input.withPayload(new AttributedIndicators(
                attributor.attribute(payload.text(), payload.rawIndicators())));
    }
}
