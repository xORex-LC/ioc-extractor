package com.iocextractor.application.pipeline.stage;

import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadSourceStageTest {

    @Test
    void reads_source_text_from_port() {
        var stage = new ReadSourceStage(
                source -> "text from " + source.getFileName(), StageTestSupport.DIAGNOSTICS);

        var output = stage.process(StageTestSupport.commandEnvelope(false));

        assertThat(output.payload().text()).isEqualTo("text from input.html");
        assertThat(output.meta()).isEqualTo(StageTestSupport.commandEnvelope(false).meta());
    }

    @Test
    void attaches_run_warning_when_reader_returns_empty_text() {
        var stage = new ReadSourceStage(source -> "  ", StageTestSupport.DIAGNOSTICS);

        var output = stage.process(StageTestSupport.commandEnvelope(false));

        assertThat(output.payload().text()).isBlank();
        assertThat(output.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(SourceDiagnosticCodes.EMPTY_TEXT);
            assertThat(diagnostic.context()).containsKey("source");
        });
    }
}
