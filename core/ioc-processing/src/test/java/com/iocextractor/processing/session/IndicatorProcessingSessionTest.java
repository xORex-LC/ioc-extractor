package com.iocextractor.processing.session;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.processing.classification.IndicatorClassifier;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndicatorProcessingSessionTest {
    @Test
    void classificationIncludesSourceSectionAndTypeInItsKey() {
        var calls = new AtomicInteger();
        var classifier = classifier(calls);
        try (var session = new IndicatorProcessingSession(classifier)) {
            var first = indicator("same.example", IndicatorType.DOMAIN, "first", "one");
            assertThat(session.classify(first)).isSameAs(session.classify(first));
            assertThat(session.classify(indicator("same.example", IndicatorType.DOMAIN, "second", "one"))
                    .match().urlMatch()).isEqualTo("second");
            session.classify(indicator("same.example", IndicatorType.DOMAIN, "first", "two"));
            session.classify(indicator("same.example", IndicatorType.URL, "first", "one"));
            assertThat(calls).hasValue(4);
        }
    }

    @Test
    void hostReuseReconstructsCurrentSourceAndClassificationKeepsDerivedType() {
        var calls = new AtomicInteger();
        try (var session = new IndicatorProcessingSession(classifier(calls))) {
            var first = indicator("same.example", IndicatorType.URL, "first", null);
            var second = indicator("same.example", IndicatorType.URL, "second", null);
            var host = session.deriveHost(first).indicator();
            assertThat(host.type()).isEqualTo(IndicatorType.DOMAIN);
            assertThat(session.deriveHost(second).indicator().source()).isEqualTo(second.source());
            session.classify(first);
            session.classify(host);
            session.classify(session.deriveHost(first).indicator());
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void aDifferentOperationPolicyNeverUsesThePinnedClassification() {
        var calls = new AtomicInteger();
        var policyCalls = new AtomicInteger();
        var pinned = classifier(calls);
        var foreign = new IndicatorClassifier(value -> {
            policyCalls.incrementAndGet();
            return new ClassificationDecision(new IndicatorFeatures(value.value(), value.value(),
                    false, false, false, HostKind.REGISTRABLE), 0, List.of(),
                    new MaskMatch("foreign-policy", null));
        });
        var indicator = indicator("same.example", IndicatorType.DOMAIN, "source", null);
        try (var session = new IndicatorProcessingSession(pinned)) {
            session.classify(indicator);
            assertThat(session.classifyWith(indicator, foreign).match().urlMatch()).isEqualTo("foreign-policy");
            assertThat(session.classifyWith(indicator, foreign).match().urlMatch()).isEqualTo("foreign-policy");
            session.classifyWith(indicator, pinned);
            assertThat(calls).hasValue(1);
            assertThat(policyCalls).hasValue(2);
        }
    }

    @Test
    void capacitySizeAndOversizedBypassNeverChangeDecisions() {
        var calls = new AtomicInteger();
        var classifier = classifier(calls);
        var small = indicator("small.example", IndicatorType.DOMAIN, "source", null);
        var other = indicator("other.example", IndicatorType.DOMAIN, "source", null);
        try (var session = new IndicatorProcessingSession(classifier, 1, 10_000)) {
            session.classify(small);
            session.classify(small);
            session.deriveHost(other);
            session.classify(other);
            session.classify(other);
            assertThat(calls).hasValue(3);
        }
        calls.set(0);
        try (var session = new IndicatorProcessingSession(classifier, 10, 1)) {
            assertThat(session.classify(small)).isEqualTo(session.classify(small));
            assertThat(calls).hasValue(2);
        }
        calls.set(0);
        try (var session = new IndicatorProcessingSession(classifier)) {
            var large = indicator("x".repeat(40_000), IndicatorType.DOMAIN, "source", null);
            assertThat(session.classify(large)).isEqualTo(session.classify(large));
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void failuresAreNotCachedAndClosedScopesRejectReuse() {
        var calls = new AtomicInteger();
        var classifier = new IndicatorClassifier(indicator -> {
            calls.incrementAndGet();
            throw new IllegalStateException("policy defect");
        });
        var session = new IndicatorProcessingSession(classifier);
        var indicator = indicator("https://", IndicatorType.URL, "source", null);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> session.classify(indicator)).hasMessage("policy defect");
            assertThat(session.deriveHost(indicator).isAvailable()).isFalse();
        }
        assertThat(calls).hasValue(2);
        session.close();
        session.close();
        assertThatThrownBy(() -> session.classify(indicator)).hasMessageContaining("closed");
        assertThatThrownBy(() -> session.deriveHost(indicator)).hasMessageContaining("closed");
        assertThatThrownBy(() -> new IndicatorProcessingSession(classifier, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndicatorProcessingSession(classifier, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(10)
    void concurrentSessionsDoNotShareClassificationsOrSources() throws Exception {
        var calls = new AtomicInteger();
        var classifier = classifier(calls);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 4; index++) {
                String source = "source-" + index;
                futures.add(workers.submit(() -> {
                    try (var session = new IndicatorProcessingSession(classifier)) {
                        var indicator = indicator("same.example", IndicatorType.DOMAIN, source, null);
                        session.classify(indicator);
                        return session.classify(indicator).match().urlMatch();
                    }
                }));
            }
            for (int index = 0; index < futures.size(); index++) {
                assertThat(futures.get(index).get(5, TimeUnit.SECONDS)).isEqualTo("source-" + index);
            }
            workers.shutdown();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(calls).hasValue(4);
    }

    private static IndicatorClassifier classifier(AtomicInteger calls) {
        return new IndicatorClassifier(indicator -> {
            calls.incrementAndGet();
            return new ClassificationDecision(new IndicatorFeatures(indicator.value(), indicator.value(),
                    false, false, false, HostKind.REGISTRABLE), 0, List.of("source-sensitive"),
                    new MaskMatch(indicator.source().label(), null));
        });
    }

    private static Indicator indicator(String value, IndicatorType type, String label, String section) {
        return new Indicator(value, type, new SourceContext(label, section));
    }
}
