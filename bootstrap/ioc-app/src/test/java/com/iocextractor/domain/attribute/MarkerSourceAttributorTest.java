package com.iocextractor.domain.attribute;

import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
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
        List<Indicator> out = attributor.attribute(text,
                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, text.indexOf("example.com"))));

        assertThat(out).singleElement()
                .extracting(i -> i.source().label()).isEqualTo("БИБ-123");
    }

    @Test
    void indicator_before_first_marker_has_null_source() {
        String text = "example.com later БИБ-123";
        List<Indicator> out = attributor.attribute(text,
                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)));

        assertThat(out).singleElement()
                .extracting(i -> i.source().label()).isNull();
    }

    @Test
    void no_marker_in_text_yields_null_source() {
        String text = "just example.com here";
        List<Indicator> out = attributor.attribute(text,
                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, text.indexOf("example.com"))));

        assertThat(out).singleElement()
                .extracting(i -> i.source().label()).isNull();
    }
}
