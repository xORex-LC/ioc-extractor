package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
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
    public StageId name() {
        return StageNames.REFANG;
    }

    @Override
    public Envelope<RefangedText> process(Envelope<SourceText> input) {
        return input.withPayload(new RefangedText(refanger.refang(input.payload().text())));
    }
}
