package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.iocextractor.application.pipeline.payload.SourceText;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;

import java.util.Objects;

/**
 * Reads source document text through the {@link SourceReader} port.
 */
public final class ReadSourceStage implements Stage<ExtractionCommand, SourceText> {

    private final SourceReader reader;
    private final DiagnosticFactory diagnosticFactory;

    /**
     * Creates the stage.
     *
     * @param reader source reader port
     * @param diagnosticFactory factory for source outcome diagnostics
     */
    public ReadSourceStage(SourceReader reader, DiagnosticFactory diagnosticFactory) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    @Override
    public StageId name() {
        return StageNames.READ_SOURCE;
    }

    @Override
    public Envelope<SourceText> process(Envelope<ExtractionCommand> input) {
        var text = reader.readText(input.payload().source());
        var output = input.withPayload(new SourceText(text));
        if (text.isBlank()) {
            return output.withDiagnostic(diagnosticFactory.create(SourceDiagnosticCodes.EMPTY_TEXT)
                    .with("source", input.payload().source())
                    .build());
        }
        return output;
    }
}
