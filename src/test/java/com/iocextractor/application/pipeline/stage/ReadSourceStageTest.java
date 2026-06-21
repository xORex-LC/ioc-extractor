package com.iocextractor.application.pipeline.stage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadSourceStageTest {

    @Test
    void reads_source_text_from_port() {
        var stage = new ReadSourceStage(source -> "text from " + source.getFileName());

        var output = stage.process(StageTestSupport.commandEnvelope(false));

        assertThat(output.payload().text()).isEqualTo("text from input.html");
        assertThat(output.meta()).isEqualTo(StageTestSupport.commandEnvelope(false).meta());
    }
}
