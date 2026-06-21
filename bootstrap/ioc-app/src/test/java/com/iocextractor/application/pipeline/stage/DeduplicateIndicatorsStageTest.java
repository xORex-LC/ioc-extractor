package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.domain.model.Indicator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicateIndicatorsStageTest {

    @Test
    void removes_within_batch_duplicates_and_lookup_hits_when_enabled() {
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var existing = StageTestSupport.indicator("existing.example");
        var stage = new DeduplicateIndicatorsStage(new Lookup(Set.of(existing.dedupKey())), true);

        var output = stage.process(StageTestSupport.envelope(
                new AttributedIndicators(List.of(first, duplicate, existing)), false));

        assertThat(output.payload().extracted()).containsExactly(first, duplicate, existing);
        assertThat(output.payload().retained()).containsExactly(first);
    }

    @Test
    void keeps_all_indicators_when_deduplication_disabled() {
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var stage = new DeduplicateIndicatorsStage(new Lookup(Set.of(first.dedupKey())), false);

        var output = stage.process(StageTestSupport.envelope(
                new AttributedIndicators(List.of(first, duplicate)), false));

        assertThat(output.payload().retained()).containsExactly(first, duplicate);
    }

    private record Lookup(Set<String> keys) implements com.iocextractor.application.port.out.LookupRepository {

        @Override
        public boolean contains(Indicator indicator) {
            return keys.contains(indicator.dedupKey());
        }

        @Override
        public long maxId() {
            return 0;
        }
    }
}
