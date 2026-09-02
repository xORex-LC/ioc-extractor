package com.iocextractor.domain.extract;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegexIndicatorExtractorBoundaryTest {

    @ParameterizedTest
    @NullAndEmptySource
    void absent_text_produces_an_empty_outcome_without_compiling_patterns(String text) {
        PatternEngine unusedEngine = new PatternEngine() {
            @Override
            public String id() {
                return "unused";
            }

            @Override
            public Compiled compile(String regex) {
                throw new AssertionError("no pattern should be compiled");
            }
        };
        RegexIndicatorExtractor extractor = new RegexIndicatorExtractor(unusedEngine, Map.of());

        ExtractionOutcome outcome = extractor.extract(text);

        assertThat(outcome.indicators()).isEmpty();
        assertThat(outcome.decisions()).isEmpty();
    }
}
