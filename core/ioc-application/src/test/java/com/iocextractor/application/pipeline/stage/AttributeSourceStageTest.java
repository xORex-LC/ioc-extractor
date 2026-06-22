package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeSourceStageTest {

    @Test
    void attributes_raw_indicators() {
        var raw = new RawIndicator("example.com", IndicatorType.DOMAIN, 0);
        var indicator = StageTestSupport.indicator("example.com");
        var stage = new AttributeSourceStage((text, indicators) -> List.of(indicator), StageTestSupport.CLOCK);

        var output = stage.process(StageTestSupport.envelope(
                new ExtractedIndicators("example.com", List.of(raw)), false));

        assertThat(output.payload().indicators()).containsExactly(indicator);
    }

    @Test
    void warns_when_an_indicator_has_no_source() {
        var orphan = new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext(null, null));
        var stage = new AttributeSourceStage((text, indicators) -> List.of(orphan), StageTestSupport.CLOCK);

        var output = stage.process(StageTestSupport.envelope(new ExtractedIndicators(
                "example.com", List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0))), false));

        assertThat(output.diagnostics()).extracting(d -> d.code().id())
                .containsExactly("SOURCE.MARKERS_UNMATCHED");
    }

    @Test
    void no_diagnostic_when_all_indicators_are_attributed() {
        var attributed = new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext("Letter X", null));
        var stage = new AttributeSourceStage((text, indicators) -> List.of(attributed), StageTestSupport.CLOCK);

        var output = stage.process(StageTestSupport.envelope(new ExtractedIndicators(
                "example.com", List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0))), false));

        assertThat(output.diagnostics()).isEmpty();
    }
}
