package com.iocextractor.application.classification;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IndicatorClassifierTest {

    @Test
    void delegates_network_classification_once() {
        var calls = new AtomicInteger();
        var expected = new ClassificationDecision(
                new IndicatorFeatures("example.com", "example.com", false, false, false, HostKind.REGISTRABLE),
                1, List.of("registrable"), new MaskMatch("u:hAS", "h:dAS"));
        var classifier = new IndicatorClassifier(indicator -> {
            calls.incrementAndGet();
            return expected;
        });

        assertThat(classifier.classify(indicator(IndicatorType.DOMAIN, "example.com"))).isSameAs(expected);
        assertThat(calls).hasValue(1);
    }

    @Test
    void supplies_neutral_file_decision_without_calling_network_policy() {
        var classifier = new IndicatorClassifier(indicator -> {
            throw new AssertionError("file IOC must not reach network policy");
        });

        var decision = classifier.classify(indicator(IndicatorType.MD5, "0123456789ABCDEF0123456789ABCDEF"));

        assertThat(decision.matchedRuleIndex()).isEqualTo(-1);
        assertThat(decision.matchedPredicates()).isEmpty();
        assertThat(decision.features().hostKind()).isEqualTo(HostKind.UNKNOWN);
        assertThat(decision.match()).isEqualTo(new MaskMatch(null, null));
    }

    private static Indicator indicator(IndicatorType type, String value) {
        return new Indicator(value, type, new SourceContext(null, null));
    }
}
