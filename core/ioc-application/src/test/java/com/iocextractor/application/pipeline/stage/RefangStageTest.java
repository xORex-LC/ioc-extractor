package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.SourceText;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.domain.refang.RefangRule;
import com.iocextractor.domain.refang.ReplacementRefanger;
import com.iocextractor.application.TestDocumentSourceWorkspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefangStageTest {

    @ParameterizedTest
    @ValueSource(strings = {"hxxps[:]//example[.]org hxxp[:]//example[.]org", "https://example.org", ""})
    void streamed_refang_preserves_ordered_text_and_operational_decisions(String text) throws Exception {
        var refanger = new ReplacementRefanger(List.of(new RefangRule("hxxps", "https"),
                new RefangRule("hxxp", "http"), new RefangRule("[:]", ":"), new RefangRule("[.]", ".")));
        var finiteTracer = new StageTestSupport.RecordingTracer();
        var expected = new RefangStage(refanger, finiteTracer)
                .process(StageTestSupport.envelope(new SourceText(text), false));
        try (var source = new TestDocumentSourceWorkspace()) {
            source.writer().write(text);
            var tracer = new StageTestSupport.RecordingTracer();
            var output = new RefangSourceStreamStage(refanger, tracer)
                    .process(StageTestSupport.envelope(source, false));
            assertThat(output.payload()).isSameAs(source);
            assertThat(source.text().toString()).isEqualTo(expected.payload().text());
            assertThat(tracer.decisions).containsExactlyElementsOf(finiteTracer.decisions);
        }
    }

    @Test
    void refangs_text() {
        var stage = new RefangStage(
                text -> new RefangOutcome(text.replace("hxxp", "http"), List.of()), StageTestSupport.TRACER);

        var output = stage.process(StageTestSupport.envelope(new SourceText("hxxp://example.com"), false));

        assertThat(output.payload().text()).isEqualTo("http://example.com");
    }
}
