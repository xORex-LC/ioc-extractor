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

    @Test
    void bounded_stage_counts_survive_every_envelope_copy_and_extend_only_with_new_occurrences() {
        var collector = new com.iocextractor.diagnostics.result.BoundedDiagnosticCollector(1);
        collector.add(diagnostic());
        collector.add(diagnostic());
        var envelope = Envelope.of("start", meta()).withDiagnostics(collector.batch());
        var next = envelope.withPayload(42).atStage(new StageId("EXTRACT"))
                .withMetaAttribute("rows", 5).withDiagnostic(diagnostic()).withDiagnostics(List.of(diagnostic()));

        assertThat(envelope.diagnosticSummary().total()).isEqualTo(2);
        assertThat(envelope.diagnosticSummary().suppressed()).isOne();
        assertThat(next.diagnosticSummary().total()).isEqualTo(4);
        assertThat(next.diagnosticSummary().suppressed()).isOne();
        assertThat(next.diagnostics()).hasSize(3);
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
