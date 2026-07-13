package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadTypeTest {

    @Test
    void collection_payloads_are_defensively_copied() {
        var raw = new ArrayList<>(List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)));
        var extracted = new ExtractedIndicators("example.com", new ExtractionOutcome(raw, List.of()));
        raw.clear();

        assertThat(extracted.rawIndicators()).hasSize(1);
        assertThatThrownBy(() -> extracted.rawIndicators().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void artifact_summary_preserves_order_and_is_immutable() {
        var written = new LinkedHashMap<String, Integer>();
        written.put("masks", 2);
        written.put("hashes", 1);

        var summary = new ArtifactWriteSummary(3, 3, written);
        written.clear();

        assertThat(summary.writtenPerArtifact().keySet()).containsExactly("masks", "hashes");
        assertThatThrownBy(() -> summary.writtenPerArtifact().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void retained_indicators_copy_lists() {
        var classified = new ClassifiedIndicator(indicator("example.com"),
                new com.iocextractor.domain.classify.ClassificationDecision(
                        new com.iocextractor.domain.feature.IndicatorFeatures(
                                "example.com", "example.com", false, false, false,
                                com.iocextractor.domain.feature.HostKind.REGISTRABLE),
                        0, List.of(), new com.iocextractor.domain.model.MaskMatch("u:hAS", "h:dAS")));
        var indicators = new ArrayList<>(List.of(classified));

        var retained = new RetainedIndicators(1, indicators);
        indicators.clear();

        assertThat(retained.extracted()).isEqualTo(1);
        assertThat(retained.retained()).containsExactly(classified);
    }

    @Test
    void deduplicated_indicators_copy_retained_list() {
        var indicator = indicator("example.com");
        var indicators = new ArrayList<>(List.of(indicator));

        var decisions = List.of(new DeduplicationDecision(indicator, true),
                new DeduplicationDecision(indicator, false));
        var deduplicated = new DeduplicatedIndicators(2, indicators, decisions);
        indicators.clear();

        assertThat(deduplicated.extracted()).isEqualTo(2);
        assertThat(deduplicated.retained()).containsExactly(indicator);
    }

    private Indicator indicator(String value) {
        return new Indicator(value, IndicatorType.DOMAIN, new SourceContext(null, null));
    }
}
