package com.iocextractor.application.pipeline.stage;

import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import com.iocextractor.application.TestDocumentSourceWorkspace;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadSourceStageTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void streamed_reader_finishes_owned_writer_before_inspection_and_propagates_close_failure(boolean failClose) {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var writer = new java.io.StringWriter() {
            public String toString() { assertThat(closed).isTrue(); return super.toString(); }
            public void close() throws java.io.IOException {
                closed.set(true);
                if (failClose) { throw new java.io.IOException("spool finish failed"); }
            }
        };
        var source = new TestDocumentSourceWorkspace(writer);
        var owner = StageTestSupport.sourceOwner(source);
        var stage = new ReadSourceStreamStage(TestDocumentSourceWorkspace.reader(path -> "text"), StageTestSupport.DIAGNOSTICS);
        var input = StageTestSupport.commandEnvelope(false)
                .withMetaAttribute(PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE, owner);
        if (failClose) {
            assertThatThrownBy(() -> stage.process(input)).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasRootCauseMessage("spool finish failed");
        } else {
            assertThat(stage.process(input).payload()).isSameAs(source);
        }
        assertThat(closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n\u2003", " \t\ud83d\ude00 IOC"})
    void streamed_read_preserves_unicode_text_and_empty_source_diagnostic(String text) {
        try (var source = new TestDocumentSourceWorkspace()) {
            var owner = StageTestSupport.sourceOwner(source);
            var input = StageTestSupport.commandEnvelope(false)
                    .withMetaAttribute(PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE, owner);
            var output = new ReadSourceStreamStage(TestDocumentSourceWorkspace.reader(path -> text),
                    StageTestSupport.DIAGNOSTICS).process(input);
            assertThat(output.meta()).isEqualTo(input.meta());
            assertThat(source.text().toString()).isEqualTo(text);
            if (text.isBlank()) {
                assertThat(output.diagnostics()).singleElement().satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(SourceDiagnosticCodes.EMPTY_TEXT);
                    assertThat(diagnostic.context()).containsEntry("source", input.payload().source());
                });
            } else {
                assertThat(output.diagnostics()).isEmpty();
            }
        }
    }

    @Test
    @Timeout(5)
    void cancelled_streamed_read_stops_before_source_inspection() {
        try (var source = new TestDocumentSourceWorkspace()) {
            var owner = StageTestSupport.sourceOwner(source);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> new ReadSourceStreamStage(
                        TestDocumentSourceWorkspace.reader(path -> " \t"), StageTestSupport.DIAGNOSTICS)
                        .process(StageTestSupport.commandEnvelope(false)
                                .withMetaAttribute(PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE, owner)))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Source text processing interrupted");
            } finally {
                Thread.interrupted();
            }
        }
    }

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
