package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.MaskMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactFilterTest {

    @Test
    void artifact_filter_combines_include_and_exclude_predicates() {
        var filter = new ArtifactFilter(
                List.of(indicator -> indicator.indicator().value().startsWith("1.")),
                List.of(indicator -> indicator.indicator().value().endsWith(".5")));

        assertThat(filter.accepts(indicator("1.2.3.4", IndicatorType.IPV4))).isTrue();
        assertThat(filter.accepts(indicator("1.2.3.5", IndicatorType.IPV4))).isFalse();
        assertThat(filter.accepts(indicator("5.6.7.8", IndicatorType.IPV4))).isFalse();
    }

    private ClassifiedIndicator indicator(String value, IndicatorType type) {
        var indicator = new Indicator(value, type, new SourceContext(null, null));
        var features = new IndicatorFeatures(value, value, false, false, false, HostKind.IP);
        return new ClassifiedIndicator(indicator,
                new ClassificationDecision(features, 0, List.of(), new MaskMatch(null, null)));
    }
}
