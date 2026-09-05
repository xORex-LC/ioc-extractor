package com.iocextractor.domain.extract;

import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.support.LiteralPatternEngine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RegexIndicatorExtractorBoundaryTest {

    @ParameterizedTest
    @NullAndEmptySource
    void absent_text_produces_an_empty_outcome_without_matching_patterns(String text) {
        RegexIndicatorExtractor extractor = new RegexIndicatorExtractor(
                new LiteralPatternEngine(), Map.of(IndicatorType.DOMAIN, "example.test"));

        ExtractionOutcome outcome = extractor.extract(text);

        assertThat(outcome.indicators()).isEmpty();
        assertThat(outcome.decisions()).isEmpty();
    }

    @Test
    void priority_claims_overlaps_while_indicators_remain_in_source_order() {
        String url = "https://bad.test/path";
        String domain = "bad.test";
        String text = domain + " then " + url;
        var patterns = new LinkedHashMap<IndicatorType, String>();
        patterns.put(IndicatorType.URL, url);
        patterns.put(IndicatorType.DOMAIN, domain);
        RegexIndicatorExtractor extractor = new RegexIndicatorExtractor(
                new LiteralPatternEngine(), patterns);

        ExtractionOutcome outcome = extractor.extract(text);

        assertThat(outcome.indicators())
                .extracting(RawIndicator::value, RawIndicator::type, RawIndicator::position)
                .containsExactly(
                        tuple(domain, IndicatorType.DOMAIN, 0),
                        tuple(url, IndicatorType.URL, text.indexOf(url)));
        assertThat(outcome.decisions())
                .extracting(
                        ExtractionDecision::type,
                        decision -> decision.span().value(),
                        ExtractionDecision::status)
                .containsExactly(
                        tuple(IndicatorType.URL, url, ExtractionDecisionStatus.ACCEPTED),
                        tuple(IndicatorType.DOMAIN, domain, ExtractionDecisionStatus.ACCEPTED),
                        tuple(IndicatorType.DOMAIN, domain, ExtractionDecisionStatus.DROPPED_OVERLAP));
    }
}
