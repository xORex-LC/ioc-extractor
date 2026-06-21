package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.Stage;
import com.iocextractor.application.pipeline.StageName;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.application.pipeline.payload.SourceText;
import com.iocextractor.domain.refang.Refanger;

import java.util.Objects;

/**
 * Applies configured refang replacements to source text.
 */
public final class RefangStage implements Stage<SourceText, RefangedText> {

    private final Refanger refanger;

    /**
     * Creates the stage.
     *
     * @param refanger refang service
     */
    public RefangStage(Refanger refanger) {
        this.refanger = Objects.requireNonNull(refanger, "refanger");
    }

    @Override
    public StageName name() {
        return StageName.REFANG;
    }

    @Override
    public Envelope<RefangedText> process(Envelope<SourceText> input) {
        return input.withPayload(new RefangedText(refanger.refang(input.payload().text())));
    }
}
