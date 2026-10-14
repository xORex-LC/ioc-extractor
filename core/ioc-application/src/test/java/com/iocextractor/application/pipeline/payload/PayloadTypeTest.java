package com.iocextractor.application.pipeline.payload;

import com.iocextractor.processing.model.ClassifiedIndicator;

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
    void occurrenceRejectsInvalidCoordinatesAndKeepsObservationSelection() {
        assertThatThrownBy(() -> new IndicatorOccurrence(indicator("example.com"), -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndicatorOccurrence(indicator("example.com"), 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        var occurrence = new IndicatorOccurrence(indicator("example.com"), 3, 2, false);
        assertThat(occurrence.retainedObservation()).isFalse();
        assertThat(occurrence.orderingPosition().value()).isEqualTo((3L << 32) | 2);
    }

    private Indicator indicator(String value) {
        return new Indicator(value, IndicatorType.DOMAIN, new SourceContext(null, null));
    }
}
