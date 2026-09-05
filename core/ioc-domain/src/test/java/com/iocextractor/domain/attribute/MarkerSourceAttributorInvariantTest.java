package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.support.LiteralPatternEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MarkerSourceAttributorInvariantTest {

    @Test
    void markers_are_sorted_normalized_and_applied_at_the_inclusive_boundary() {
        String first = "FIRST\u00A0  LABEL";
        String second = "SECOND   LABEL";
        String text = "orphan " + first + " middle " + second + " tail";
        int firstPosition = text.indexOf(first);
        int secondPosition = text.indexOf(second);
        var attributor = new MarkerSourceAttributor(
                new LiteralPatternEngine(), List.of(second, first));
        List<RawIndicator> raw = List.of(
                raw("orphan.test", 0),
                raw("first-boundary.test", firstPosition),
                raw("middle.test", firstPosition + first.length()),
                raw("second-boundary.test", secondPosition),
                raw("tail.test", text.length()));

        AttributionOutcome outcome = attributor.attribute(text, raw);

        assertThat(outcome.markers()).containsExactly(
                new SourceMarker(firstPosition, "FIRST LABEL"),
                new SourceMarker(secondPosition, "SECOND LABEL"));
        assertThat(outcome.indicators())
                .extracting(indicator -> indicator.source().label())
                .containsExactly(null, "FIRST LABEL", "FIRST LABEL", "SECOND LABEL", "SECOND LABEL");
        assertThat(outcome.indicators())
                .extracting(Indicator::value)
                .containsExactlyElementsOf(raw.stream().map(RawIndicator::value).toList());
    }

    @Test
    void source_marker_accepts_zero_and_rejects_negative_positions() {
        assertThat(new SourceMarker(0, "label").position()).isZero();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceMarker(-1, "label"))
                .withMessage("position must be non-negative");
    }

    private static RawIndicator raw(String value, int position) {
        return new RawIndicator(value, IndicatorType.DOMAIN, position);
    }
}
