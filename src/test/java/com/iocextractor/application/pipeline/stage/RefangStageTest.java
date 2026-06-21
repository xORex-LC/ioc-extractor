package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.SourceText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefangStageTest {

    @Test
    void refangs_text() {
        var stage = new RefangStage(text -> text.replace("hxxp", "http"));

        var output = stage.process(StageTestSupport.envelope(new SourceText("hxxp://example.com"), false));

        assertThat(output.payload().text()).isEqualTo("http://example.com");
    }
}
