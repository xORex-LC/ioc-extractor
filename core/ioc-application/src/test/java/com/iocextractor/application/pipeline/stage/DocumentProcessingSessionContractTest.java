package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;


import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.port.out.artifact.DocumentProcessingSession;
import com.iocextractor.diagnostics.result.Result;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentProcessingSessionContractTest {
    @Test
    void applicationClosesOneOwnedSessionAfterEachSuccessfulDocument() {
        var opened = new AtomicInteger();
        var closed = new AtomicInteger();
        var prepared = new AtomicInteger();
        var stage = stage(plan(opened, closed, prepared, false));
        var input = StageTestSupport.envelope(StageTestSupport.attributedIndicators(
                StageTestSupport.indicator("one.example"), StageTestSupport.indicator("one.example")), false);
        stage.process(input);
        stage.process(input);
        assertThat(opened).hasValue(2);
        assertThat(closed).hasValue(2);
        assertThat(prepared).hasValue(4);
    }

    @Test
    void applicationClosesSessionWhenPreparationFailsWithoutStartingAnotherObservation() {
        var opened = new AtomicInteger();
        var closed = new AtomicInteger();
        var prepared = new AtomicInteger();
        var stage = stage(plan(opened, closed, prepared, true));
        assertThatThrownBy(() -> stage.process(StageTestSupport.envelope(StageTestSupport.attributedIndicators(
                StageTestSupport.indicator("one.example"), StageTestSupport.indicator("two.example")), false)))
                .isInstanceOf(IllegalStateException.class).hasMessage("preparation failed");
        assertThat(opened).hasValue(1);
        assertThat(closed).hasValue(1);
        assertThat(prepared).hasValue(1);
    }

    private com.iocextractor.platform.etl.Stage<AttributedIndicators, PreparedArtifacts> stage(DocumentProcessingPlan plan) {
        return com.iocextractor.application.TestDocumentWorkspace.stage(plan, List.of(), (artifact, row) -> Optional.empty(), Map.of(), true);
    }

    private DocumentProcessingPlan plan(AtomicInteger opened, AtomicInteger closed,
                                        AtomicInteger prepared, boolean fail) {
        return new DocumentProcessingPlan() {
            @Override public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
                throw new AssertionError("The owned session must execute the document");
            }
            @Override public DocumentProcessingSession openSession() {
                opened.incrementAndGet();
                return new DocumentProcessingSession() {
                    @Override public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
                        prepared.incrementAndGet();
                        if (fail) { throw new IllegalStateException("preparation failed"); }
                        return Result.success(List.of());
                    }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        };
    }
}
