package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.support.LiteralPatternEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

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

    @Test
    void unordered_and_equal_positions_preserve_encounter_order_and_inclusive_source() {
        var attributor = new MarkerSourceAttributor(new LiteralPatternEngine(), List.of("A", "B"));
        var indicators = List.of(raw("last.test", 8), raw("orphan.test", 0),
                raw("boundary.test", 6), raw("same.test", 6), raw("first.test", 2));

        var outcome = attributor.attribute("..A...B..", indicators);

        assertThat(outcome.decisions()).extracting(AttributionDecision::rawIndicator)
                .containsExactlyElementsOf(indicators);
        assertThat(outcome.indicators()).extracting(indicator -> indicator.source().label())
                .containsExactly("B", null, "B", "B", "A");
    }

    @Test
    void longest_overlap_wins_and_adjacent_markers_remain_eligible() {
        var attributor = new MarkerSourceAttributor(new LiteralPatternEngine(),
                List.of("A", "ABC", "BC", "D"));

        var outcome = attributor.attribute("ABCD", List.of(raw("one.test", 0), raw("two.test", 3)));

        assertThat(outcome.markers()).containsExactly(new SourceMarker(0, "ABC"), new SourceMarker(3, "D"));
        assertThat(outcome.indicators()).extracting(indicator -> indicator.source().label())
                .containsExactly("ABC", "D");
    }

    @Test
    void seeded_ordered_and_shuffled_boundaries_match_an_independent_linear_oracle() {
        var random = new Random(302_031L);
        var attributor = new MarkerSourceAttributor(new LiteralPatternEngine(), List.of("MARK"));
        for (int trial = 0; trial < 100; trial++) {
            var text = new StringBuilder("orphan ");
            var positions = new ArrayList<Integer>();
            for (int section = 0; section < random.nextInt(40); section++) {
                positions.add(text.length());
                text.append("MARK").append(" ".repeat(random.nextInt(10) + 1));
            }
            var indicators = new ArrayList<RawIndicator>();
            indicators.add(raw("orphan.test", 0));
            for (int position : positions) {
                indicators.add(raw("before.test", position - 1));
                indicators.add(raw("equal.test", position));
                indicators.add(raw("equal-again.test", position));
                indicators.add(raw("after.test", position + 1));
            }
            indicators.add(raw("tail.test", text.length()));
            assertSources(attributor.attribute(text.toString(), indicators), indicators, positions);
            Collections.shuffle(indicators, random);
            assertSources(attributor.attribute(text.toString(), indicators), indicators, positions);
        }
    }

    private static void assertSources(AttributionOutcome outcome, List<RawIndicator> input, List<Integer> positions) {
        assertThat(outcome.decisions()).extracting(AttributionDecision::rawIndicator).containsExactlyElementsOf(input);
        for (var decision : outcome.decisions()) {
            Integer preceding = null;
            for (int marker : positions) {
                if (marker <= decision.rawIndicator().position()) {
                    preceding = marker;
                }
            }
            if (preceding == null) {
                assertThat(decision.marker()).isEmpty();
            } else {
                assertThat(decision.marker()).contains(new SourceMarker(preceding, "MARK"));
            }
        }
    }

    private static RawIndicator raw(String value, int position) {
        return new RawIndicator(value, IndicatorType.DOMAIN, position);
    }
}
