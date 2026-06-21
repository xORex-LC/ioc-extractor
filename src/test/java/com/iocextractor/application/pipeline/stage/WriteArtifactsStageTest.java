package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.domain.model.Indicator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriteArtifactsStageTest {

    @Test
    void writes_retained_indicators_to_sinks_in_order() {
        var masks = new RecordingSink("masks", 2);
        var hashes = new RecordingSink("hashes", 1);
        var indicator = StageTestSupport.indicator("example.com");
        var stage = new WriteArtifactsStage(List.of(masks, hashes));

        var output = stage.process(StageTestSupport.envelope(
                new RetainedIndicators(List.of(indicator), List.of(indicator)), false));

        assertThat(output.payload().extracted()).isEqualTo(1);
        assertThat(output.payload().retained()).isEqualTo(1);
        assertThat(output.payload().writtenPerArtifact().keySet()).containsExactly("masks", "hashes");
        assertThat(masks.received).containsExactly(indicator);
        assertThat(hashes.received).containsExactly(indicator);
    }

    @Test
    void dry_run_does_not_write_to_sinks() {
        var sink = new RecordingSink("masks", 2);
        var indicator = StageTestSupport.indicator("example.com");
        var stage = new WriteArtifactsStage(List.of(sink));

        var output = stage.process(StageTestSupport.envelope(
                new RetainedIndicators(List.of(indicator), List.of(indicator)), true));

        assertThat(output.payload().writtenPerArtifact()).isEmpty();
        assertThat(sink.received).isEmpty();
    }

    private static final class RecordingSink implements IocSink {
        private final String name;
        private final int written;
        private final List<Indicator> received = new ArrayList<>();

        private RecordingSink(String name, int written) {
            this.name = name;
            this.written = written;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int write(List<Indicator> indicators) {
            received.addAll(indicators);
            return written;
        }
    }
}
