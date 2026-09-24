package com.iocextractor.domain.attribute;

import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkerSourceAttributorTest {

    private final SourceAttributor attributor =
            new MarkerSourceAttributor(new Re2jPatternEngine(), List.of("БИБ-\\d+"));

    @Test
    void indicator_after_marker_gets_its_label() {
        String text = "БИБ-123 list: example.com";
        AttributionOutcome outcome = attributor.attribute(text,
                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, text.indexOf("example.com"))));

        assertThat(outcome.indicators()).singleElement()
                .extracting(i -> i.source().label()).isEqualTo("БИБ-123");
        assertThat(outcome.markers()).containsExactly(new SourceMarker(0, "БИБ-123"));
        assertThat(outcome.decisions()).singleElement()
                .satisfies(decision -> assertThat(decision.marker()).contains(new SourceMarker(0, "БИБ-123")));
    }

    @Test
    void indicator_before_first_marker_has_null_source() {
        String text = "example.com later БИБ-123";
        AttributionOutcome outcome = attributor.attribute(text,
                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)));

        assertThat(outcome.indicators()).singleElement()
                .extracting(i -> i.source().label()).isNull();
    }

    @Test
    void no_marker_in_text_yields_null_source() {
        String text = "just example.com here";
        AttributionOutcome outcome = attributor.attribute(text,
                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, text.indexOf("example.com"))));

        assertThat(outcome.indicators()).singleElement()
                .extracting(i -> i.source().label()).isNull();
    }

    @Test
    void overlapping_marker_patterns_keep_the_longest_complete_label() {
        var overlapping = new MarkerSourceAttributor(new Re2jPatternEngine(), List.of(
                "ФСТЭК_\\d+(?:/\\d+)+",
                "ФСТЭК_(?:\\d{2}\\.\\d{2}\\.\\d{4}_)?\\d+(?:/\\d+)+"));
        String label = "ФСТЭК_02.04.2026_240/93/2124";
        String text = label + " example.com";

        AttributionOutcome outcome = overlapping.attribute(text, List.of(
                new RawIndicator("example.com", IndicatorType.DOMAIN, text.indexOf("example.com"))));

        assertThat(outcome.markers()).containsExactly(new SourceMarker(0, label));
        assertThat(outcome.indicators()).singleElement()
                .extracting(indicator -> indicator.source().label()).isEqualTo(label);
    }

    @Test
    void nonbreaking_spaces_are_matched_and_normalized_without_changing_positions() {
        var fstec = new MarkerSourceAttributor(new Re2jPatternEngine(), List.of(
                "ФСТЭК\\s+\\d{2}\\.\\d{2}\\.\\d{4}\\s+№\\s*\\d+(?:/\\d+)+"));
        String text = "ФСТЭК\u00A021.05.2025\u00A0№240/93/1329: example.com";

        AttributionOutcome outcome = fstec.attribute(text, List.of(
                new RawIndicator("example.com", IndicatorType.DOMAIN, text.indexOf("example.com"))));

        assertThat(outcome.markers()).containsExactly(
                new SourceMarker(0, "ФСТЭК 21.05.2025 №240/93/1329"));
        assertThat(outcome.indicators()).singleElement()
                .extracting(indicator -> indicator.source().label())
                .isEqualTo("ФСТЭК 21.05.2025 №240/93/1329");
    }
}
