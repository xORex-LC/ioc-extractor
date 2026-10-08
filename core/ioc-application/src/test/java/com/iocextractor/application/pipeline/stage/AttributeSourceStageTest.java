package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.application.TestDocumentSourceWorkspace;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.attribute.MarkerCursor;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.attribute.SourceMarker;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeSourceStageTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void streamed_attribution_preserves_inclusive_markers_unattributed_rows_and_trace(boolean trailingMarkers) throws Exception {
        var orphan = new RawIndicator("orphan.org", IndicatorType.DOMAIN, 0);
        var attributed = new RawIndicator("attributed.org", IndicatorType.DOMAIN, 30);
        var selected = new SourceMarker(30, "selected-section");
        var markers = trailingMarkers ? List.of(selected, new SourceMarker(50, "unused-section"),
                new SourceMarker(70, "last-section")) : List.of(selected);
        var expected = new AttributionOutcome(markers, List.of(
                new AttributionDecision(orphan, Optional.empty()), new AttributionDecision(attributed, Optional.of(selected))));
        String text = "x".repeat(100);
        var markerCursor = new MarkerCursor() {
            private int index = -1;
            public boolean next() { return ++index < markers.size(); }
            public SourceMarker value() { return markers.get(index); }
        };
        var attributor = new SourceAttributor() {
            public AttributionOutcome attribute(String input, List<RawIndicator> indicators) {
                assertThat(input).isEqualTo(text);
                assertThat(indicators).containsExactly(orphan, attributed);
                return expected;
            }
            public MarkerCursor markers(CharSequence input, int limit) {
                assertThat(input.toString()).isEqualTo(text);
                assertThat(limit).isEqualTo(65536);
                return markerCursor;
            }
        };
        var finiteTracer = new StageTestSupport.RecordingTracer();
        var finite = new AttributeSourceStage(attributor, StageTestSupport.CLOCK, finiteTracer)
                .process(StageTestSupport.envelope(new ExtractedIndicators(text,
                        new ExtractionOutcome(List.of(orphan, attributed), List.of())), false));
        try (var source = new TestDocumentSourceWorkspace()) {
            source.writer().write(text);
            for (var raw : List.of(orphan, attributed)) {
                source.record(new ExtractionDecision(raw.type(), "fixture", new Span(raw.position(),
                        raw.position() + raw.value().length(), raw.value()), ExtractionDecisionStatus.ACCEPTED));
            }
            var tracer = new StageTestSupport.RecordingTracer();
            var output = new AttributeSourceStreamStage(attributor, StageTestSupport.CLOCK, tracer)
                    .process(StageTestSupport.envelope(source, false));
            assertThat(output.payload().indicators()).containsExactlyElementsOf(finite.payload().indicators());
            assertThat(output.payload().indicators()).extracting(value -> value.source().label())
                    .containsExactly(null, "selected-section");
            assertThat(tracer.decisions).containsExactlyElementsOf(finiteTracer.decisions);
            assertThat(output.diagnostics()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.code().id()).isEqualTo("SOURCE.MARKERS_UNMATCHED");
                assertThat(diagnostic.context()).containsEntry("unattributed", 1L).containsEntry("total", 2);
            });
            assertThat(markerCursor.next()).isFalse();
        }
    }

    @Test
    void attributes_raw_indicators() {
        var raw = new RawIndicator("example.com", IndicatorType.DOMAIN, 0);
        var indicator = StageTestSupport.indicator("example.com");
        var stage = new AttributeSourceStage(
                (text, indicators) -> StageTestSupport.attributionOutcome(indicator),
                StageTestSupport.CLOCK, StageTestSupport.TRACER);

        var output = stage.process(StageTestSupport.envelope(
                new ExtractedIndicators("example.com", new ExtractionOutcome(
                        List.of(raw), List.of())), false));

        assertThat(output.payload().indicators()).containsExactly(indicator);
    }

    @Test
    void warns_when_an_indicator_has_no_source() {
        var orphan = new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext(null, null));
        var stage = new AttributeSourceStage(
                (text, indicators) -> StageTestSupport.attributionOutcome(orphan),
                StageTestSupport.CLOCK, StageTestSupport.TRACER);

        var output = stage.process(StageTestSupport.envelope(new ExtractedIndicators(
                "example.com", new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of())), false));

        assertThat(output.diagnostics()).extracting(d -> d.code().id())
                .containsExactly("SOURCE.MARKERS_UNMATCHED");
    }

    @Test
    void no_diagnostic_when_all_indicators_are_attributed() {
        var attributed = new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext("Letter X", null));
        var stage = new AttributeSourceStage(
                (text, indicators) -> StageTestSupport.attributionOutcome(attributed),
                StageTestSupport.CLOCK, StageTestSupport.TRACER);

        var output = stage.process(StageTestSupport.envelope(new ExtractedIndicators(
                "example.com", new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of())), false));

        assertThat(output.diagnostics()).isEmpty();
    }
}
