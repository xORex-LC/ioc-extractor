package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedNotificationTest {

    private static final DiagnosticFactory FACTORY = new DiagnosticFactory(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void caps_retained_diagnostics_and_reports_suppression() {
        var notification = new BoundedNotification(1, FACTORY);

        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "two").with("stage", "test").with("reason", "bad").build());

        assertThat(notification.diagnostics()).extracting(diagnostic -> diagnostic.code().id())
                .containsExactly("PIPELINE.ITEM_SKIPPED", "PIPELINE.DIAGNOSTICS_SUPPRESSED");
        assertThat(notification.summary().suppressed()).isOne();
    }

    @Test
    void first_rejecting_diagnostic_is_never_hidden_by_budget() {
        var notification = new BoundedNotification(1, FACTORY);
        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "failed").build());

        assertThat(notification.diagnostics().getFirst().code()).isEqualTo(PipelineDiagnosticCodes.STAGE_FAILED);
        assertThat(notification.summary().hasErrors()).isTrue();
    }

    @Test
    void fatal_supersedes_retained_error_for_collect_and_continue_policy() {
        var notification = new BoundedNotification(1, FACTORY);
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "error").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .severity(DiagnosticSeverity.FATAL)
                .with("stage", "test").with("reason", "fatal").build());

        assertThat(notification.diagnostics().getFirst().severity())
                .isEqualTo(DiagnosticSeverity.FATAL);
    }

    @Test
    void operation_diagnostic_stays_visible_without_consuming_or_being_displaced_by_budget() {
        var notification = new BoundedNotification(1, FACTORY);
        var operationWarning = FACTORY.create(IngestDiagnosticCodes.SOURCE_UNREADABLE)
                .severity(DiagnosticSeverity.WARN)
                .with("source", "source-1")
                .with("reason", "lossy projection")
                .build();
        notification.add(operationWarning);
        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "failed").build());

        assertThat(notification.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .containsExactly(
                        "INGEST.SOURCE_UNREADABLE",
                        "PIPELINE.STAGE_FAILED",
                        "PIPELINE.DIAGNOSTICS_SUPPRESSED");
        assertThat(notification.summary().total()).isEqualTo(3);
        assertThat(notification.summary().suppressed()).isOne();
    }

    @Test
    void construction_bound_preserves_exact_counts_and_late_rejecting_signals() {
        var collector = new BoundedDiagnosticCollector(3);
        for (int index = 0; index < 100_000; index++) {
            collector.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                    .with("item", index).with("stage", "test").with("reason", "bad").build());
        }
        collector.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "late error").build());
        collector.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .severity(DiagnosticSeverity.FATAL)
                .with("stage", "test").with("reason", "late fatal").build());

        var batch = collector.batch();
        assertThat(batch.retained()).extracting(diagnostic -> diagnostic.context().get("item"))
                .containsExactly(0, 1, 2, null, null);
        assertThat(batch.summary().total()).isEqualTo(100_002);
        assertThat(batch.summary().suppressed()).isEqualTo(99_997);
        assertThat(batch.summary().count(DiagnosticSeverity.ERROR)).isOne();
        assertThat(batch.summary().count(DiagnosticSeverity.FATAL)).isOne();
        var run = new BoundedNotification(3, FACTORY);
        run.addAll(batch);
        assertThat(run.summary().total()).isEqualTo(100_002);
        assertThat(run.summary().suppressed()).isEqualTo(99_999);
        assertThat(run.diagnostics()).hasSize(4);
    }

    @Test
    void merging_stage_batches_matches_direct_stream_samples_counts_and_delivery() {
        var random = new java.util.Random(302_033L);
        for (int limit : new int[]{1, 3, 128}) {
            var direct = new BoundedNotification(limit, FACTORY);
            var merged = new BoundedNotification(limit, FACTORY);
            var directDelivery = new java.util.ArrayList<com.iocextractor.diagnostics.Diagnostic>();
            var mergedDelivery = new java.util.ArrayList<com.iocextractor.diagnostics.Diagnostic>();
            for (int stage = 0; stage < 4; stage++) {
                var collector = new BoundedDiagnosticCollector(limit);
                for (int item = 0; item < 1_000; item++) {
                    var severity = com.iocextractor.diagnostics.DiagnosticSeverity.values()[random.nextInt(6)];
                    var code = item % 100 == 0 ? IngestDiagnosticCodes.SOURCE_UNREADABLE
                            : PipelineDiagnosticCodes.STAGE_FAILED;
                    var diagnostic = FACTORY.create(code).severity(severity)
                            .with("stage", stage).with("source", stage).with("reason", item).build();
                    collector.add(diagnostic);
                    if (direct.offer(diagnostic)) {
                        directDelivery.add(diagnostic);
                    }
                }
                merged.addAll(collector.batch(), mergedDelivery::add);
            }
            assertThat(merged.summary()).isEqualTo(direct.summary());
            assertThat(merged.diagnostics()).containsExactlyElementsOf(direct.diagnostics());
            assertThat(mergedDelivery).containsExactlyElementsOf(directDelivery);
        }
    }

    @Test
    void incomplete_or_hidden_rejecting_batch_is_rejected_and_delivery_failure_propagates() {
        var warning = FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build();
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new DiagnosticBatch(java.util.List.of(warning), DiagnosticSummary.empty()));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new DiagnosticBatch(java.util.List.of(), new DiagnosticSummary(1, 1,
                        java.util.Map.of(DiagnosticSeverity.ERROR, 1L))));
        var collector = new BoundedDiagnosticCollector(1);
        collector.add(warning);
        var failure = new IllegalStateException("collector unavailable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new BoundedNotification(1, FACTORY).addAll(collector.batch(), diagnostic -> { throw failure; }))
                .isSameAs(failure);
    }
}
