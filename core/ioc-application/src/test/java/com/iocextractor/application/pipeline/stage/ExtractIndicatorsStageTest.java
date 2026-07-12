package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractIndicatorsStageTest {

    @Test
    void extracts_raw_indicators_and_keeps_text_for_next_stage() {
        var raw = new RawIndicator("example.com", IndicatorType.DOMAIN, 0);
        var stage = new ExtractIndicatorsStage(text -> new ExtractionOutcome(List.of(raw), List.of()));

        var output = stage.process(StageTestSupport.envelope(
                new RefangedText(new RefangOutcome("example.com", List.of())), false));

        assertThat(output.payload().text()).isEqualTo("example.com");
        assertThat(output.payload().rawIndicators()).containsExactly(raw);
    }
}
