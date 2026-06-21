package com.iocextractor.platform.etl;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void meta_is_clock_controlled_and_carries_source_identity() {
        var meta = EnvelopeMeta.initial("run-1", "source-1", CLOCK)
                .withAttribute("dryRun", true);

        assertThat(meta.runId()).isEqualTo("run-1");
        assertThat(meta.sourceId()).isEqualTo("source-1");
        assertThat(meta.stage()).isEqualTo(StageId.INITIAL);
        assertThat(meta.createdAt()).isEqualTo(CLOCK.instant());
        assertThat(meta.booleanAttribute("dryRun", false)).isTrue();
        assertThat(meta.stringAttribute("dryRun")).isEqualTo("true");
    }

    @Test
    void envelope_copies_diagnostics_and_returns_new_instances_for_changes() {
        var diagnostics = new ArrayList<Diagnostic>();
        diagnostics.add(diagnostic());
        var envelope = new Envelope<>("payload", meta(), diagnostics);
        diagnostics.clear();

        var next = envelope.withPayload(42)
                .atStage(new StageId("EXTRACT"))
                .withMetaAttribute("rows", 5);

        assertThat(envelope.payload()).isEqualTo("payload");
        assertThat(envelope.meta().stage()).isEqualTo(StageId.INITIAL);
        assertThat(envelope.diagnostics()).containsExactly(diagnostic());
        assertThat(next.payload()).isEqualTo(42);
        assertThat(next.meta().stage()).isEqualTo(new StageId("EXTRACT"));
        assertThat(next.meta().attributes()).containsEntry("rows", 5);
    }

    @Test
    void diagnostics_snapshot_is_immutable() {
        var envelope = new Envelope<>("payload", meta(), List.of(diagnostic()));

        assertThatThrownBy(() -> envelope.diagnostics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private EnvelopeMeta meta() {
        return EnvelopeMeta.initial("run-1", "source.html", CLOCK);
    }

    private Diagnostic diagnostic() {
        return Diagnostic.builder(PipelineDiagnosticCodes.ITEM_SKIPPED, CLOCK)
                .with("item", "x")
                .with("stage", "test")
                .with("reason", "test")
                .build();
    }
}
