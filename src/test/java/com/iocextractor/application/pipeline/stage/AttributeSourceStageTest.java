package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeSourceStageTest {

    @Test
    void attributes_raw_indicators() {
        var raw = new RawIndicator("example.com", IndicatorType.DOMAIN, 0);
        var indicator = StageTestSupport.indicator("example.com");
        var stage = new AttributeSourceStage((text, indicators) -> List.of(indicator));

        var output = stage.process(StageTestSupport.envelope(
                new ExtractedIndicators("example.com", List.of(raw)), false));

        assertThat(output.payload().indicators()).containsExactly(indicator);
    }
}
