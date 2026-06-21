package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.Stage;
import com.iocextractor.application.pipeline.StageName;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.domain.extract.IndicatorExtractor;

import java.util.Objects;

/**
 * Extracts raw indicators from refanged text.
 */
public final class ExtractIndicatorsStage implements Stage<RefangedText, ExtractedIndicators> {

    private final IndicatorExtractor extractor;

    /**
     * Creates the stage.
     *
     * @param extractor indicator extractor
     */
    public ExtractIndicatorsStage(IndicatorExtractor extractor) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    public StageName name() {
        return StageName.EXTRACT;
    }

    @Override
    public Envelope<ExtractedIndicators> process(Envelope<RefangedText> input) {
        var text = input.payload().text();
        return input.withPayload(new ExtractedIndicators(text, extractor.extract(text)));
    }
}
