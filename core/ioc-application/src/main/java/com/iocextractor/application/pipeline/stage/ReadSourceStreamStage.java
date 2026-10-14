package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace;
import com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Streams parser output into the admitted source spool. */
public record ReadSourceStreamStage(SourceReader reader, DiagnosticFactory diagnostics)
        implements Stage<ExtractionCommand, DocumentSourceWorkspace> {
    public StageId name() { return StageNames.READ_SOURCE; }
    public Envelope<DocumentSourceWorkspace> process(Envelope<ExtractionCommand> input) {
        var owner = (DocumentPreparationWorkspace) java.util.Objects.requireNonNull(
                input.meta().attributes().get(PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE));
        var source = owner.sourceWorkspace();
        try (var writer = source.writer()) { reader.readText(input.payload().source(), writer); }
        catch (IOException failure) { throw new UncheckedIOException("Cannot finish source text spool", failure); }
        var output = input.withPayload(source);
        boolean blank = true;
        var text = source.text();
        for (int offset = 0; offset < text.length();) {
            if (Thread.currentThread().isInterrupted()) { throw new IllegalStateException("Source text processing interrupted"); }
            int codePoint = Character.codePointAt(text, offset);
            if (!Character.isWhitespace(codePoint)) { blank = false; break; }
            offset += Character.charCount(codePoint);
        }
        if (blank) { output = output.withDiagnostic(diagnostics.create(SourceDiagnosticCodes.EMPTY_TEXT)
                .with("source", input.payload().source()).build()); }
        return output;
    }
}
