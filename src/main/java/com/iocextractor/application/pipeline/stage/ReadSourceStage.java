package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.Stage;
import com.iocextractor.application.pipeline.StageName;
import com.iocextractor.application.pipeline.payload.SourceText;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.SourceReader;

import java.util.Objects;

/**
 * Reads source document text through the {@link SourceReader} port.
 */
public final class ReadSourceStage implements Stage<ExtractionCommand, SourceText> {

    private final SourceReader reader;

    /**
     * Creates the stage.
     *
     * @param reader source reader port
     */
    public ReadSourceStage(SourceReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public StageName name() {
        return StageName.READ_SOURCE;
    }

    @Override
    public Envelope<SourceText> process(Envelope<ExtractionCommand> input) {
        var text = reader.readText(input.payload().source());
        return input.withPayload(new SourceText(text));
    }
}
