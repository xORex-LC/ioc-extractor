package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.TestCodes;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.DiagnosticCode;
import com.iocextractor.platform.diagnostics.DiagnosticImpact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BoundedNotificationTest {

    private static final DiagnosticFactory FACTORY = new DiagnosticFactory(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @ParameterizedTest
    @CsvSource({
            "WARN ERROR FATAL, 0 1 2, 2, 0 1 2",
            "WARN FATAL ERROR, 0 1,   1, 0 1",
            "ERROR WARN FATAL, 0 2,   2, 0 2",
            "FATAL ERROR WARN, 0,     0, 0",
            "WARN WARN ERROR ERROR FATAL FATAL, 0 2 4, 4, 0 2 4"
    })
    void budget_one_preserves_rejecting_signals_and_exact_delivery_order(
            String severities, String stageIndexes, String runIndexes, String deliveryIndexes) {
        var input = java.util.Arrays.stream(severities.split(" "))
                .map(DiagnosticSeverity::valueOf)
                .map(severity -> FACTORY.create(TestCodes.STAGE_FAILED).severity(severity).build())
                .toList();
        var stage = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var delivered = new ArrayList<Diagnostic>();

        stage.addAll(input);
        run.addAll(stage.batch(), delivered::add);

        assertThat(stage.batch().retained()).containsExactlyElementsOf(select(input, stageIndexes));
        assertThat(run.batch().retained()).containsExactlyElementsOf(select(input, runIndexes));
        assertThat(delivered).containsExactlyElementsOf(select(input, deliveryIndexes));
        assertThat(stage.batch().summary().total()).isEqualTo(input.size());
        assertThat(stage.batch().summary().suppressed()).isEqualTo(input.size() - select(input, stageIndexes).size());
        assertThat(run.summary().total()).isEqualTo(input.size());
        assertThat(run.summary().suppressed()).isEqualTo(input.size() - 1);
        for (var severity : DiagnosticSeverity.values()) {
            assertThat(run.summary().count(severity)).isEqualTo(input.stream().filter(d -> d.severity() == severity).count());
        }
    }

    @Test
    void smaller_stage_budget_preserves_observed_counts_but_can_lose_run_samples() {
        var first = FACTORY.create(TestCodes.ITEM_SKIPPED).with("row", 1).build();
        var second = FACTORY.create(TestCodes.ITEM_SKIPPED).with("row", 2).build();
        var error = FACTORY.create(TestCodes.STAGE_FAILED).build();
        var input = List.of(first, second, error);
        var stage = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var direct = new BoundedNotification(3, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var merged = new BoundedNotification(3, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var delivered = new ArrayList<Diagnostic>();

        stage.addAll(input);
        direct.addAll(input);
        merged.addAll(stage.batch(), delivered::add);

        assertThat(direct.batch().retained()).containsExactly(first, second, error);
        assertThat(merged.batch().retained()).containsExactly(first, error);
        assertThat(delivered).containsExactly(first, error);
        assertThat(merged.summary().total()).isEqualTo(3);
        assertThat(merged.summary().bySeverity()).isEqualTo(direct.summary().bySeverity());
        assertThat(merged.summary().suppressed()).isOne();
        assertThat(direct.summary().suppressed()).isZero();
        assertThat(merged.diagnostics().getLast().severity()).isEqualTo(DiagnosticSeverity.WARN);
    }

    @Test
    void empty_and_operation_only_batches_do_not_use_the_detail_budget() {
        var operation = FACTORY.create(TestCodes.SOURCE_UNREADABLE).build();
        var stage = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var delivered = new ArrayList<Diagnostic>();
        run.addAll(stage.batch(), delivered::add);
        assertThat(run.summary()).isEqualTo(DiagnosticSummary.empty());
        assertThat(delivered).isEmpty();

        stage.addAll(List.of(operation, operation, operation));
        run.addAll(stage.batch(), delivered::add);
        assertThat(run.batch().retained()).containsExactly(operation, operation, operation);
        assertThat(run.diagnostics()).containsExactly(operation, operation, operation);
        assertThat(run.summary().total()).isEqualTo(3);
        assertThat(run.summary().suppressed()).isZero();
        assertThat(delivered).containsExactly(operation, operation, operation);
    }

    @Test
    void operation_error_satisfies_the_rejecting_signal_without_displacing_budgeted_samples() {
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED).build();
        var operationError = FACTORY.create(TestCodes.SOURCE_UNREADABLE).build();
        var elementError = FACTORY.create(TestCodes.STAGE_FAILED).build();
        var fatal = FACTORY.create(TestCodes.READ_FAILED).build();
        var stage = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        stage.addAll(List.of(warning, operationError, elementError, fatal));
        var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var delivered = new ArrayList<Diagnostic>();

        run.addAll(stage.batch(), delivered::add);

        assertThat(stage.batch().retained()).containsExactly(warning, operationError, fatal);
        assertThat(run.batch().retained()).containsExactly(fatal, operationError);
        assertThat(delivered).containsExactly(warning, operationError, fatal);
        assertThat(run.summary().bySeverity()).containsExactlyInAnyOrderEntriesOf(Map.of(
                DiagnosticSeverity.WARN, 1L, DiagnosticSeverity.ERROR, 2L, DiagnosticSeverity.FATAL, 1L));
        assertThat(run.batch().suppressedBySeverity()).containsExactlyInAnyOrderEntriesOf(Map.of(
                DiagnosticSeverity.WARN, 1L, DiagnosticSeverity.ERROR, 1L));
    }

    @Test
    void raw_delivery_failure_leaves_the_accepted_prefix_without_retrying_or_counting_the_rest() {
        var first = FACTORY.create(TestCodes.ITEM_SKIPPED).with("row", 1).build();
        var second = FACTORY.create(TestCodes.ITEM_SKIPPED).with("row", 2).build();
        var last = FACTORY.create(TestCodes.ITEM_SKIPPED).with("row", 3).build();
        var stage = new BoundedDiagnosticCollector(2, TestCodes.DIAGNOSTICS_SUPPRESSED);
        stage.addAll(List.of(first, second, last));
        var run = new BoundedNotification(2, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var attempts = new ArrayList<Diagnostic>();
        var failure = new IllegalStateException("sink failed on second row");

        assertThatThrownBy(() -> run.addAll(stage.batch(), diagnostic -> {
            attempts.add(diagnostic);
            if (diagnostic == second) { throw failure; }
        })).isSameAs(failure);

        assertThat(attempts).containsExactly(first, second);
        assertThat(run.batch().retained()).containsExactly(first, second);
        assertThat(run.summary().total()).isEqualTo(2);
        assertThat(run.summary().suppressed()).isZero();
        assertThat(stage.batch().summary().total()).isEqualTo(3);
        assertThat(run.diagnostics()).containsExactly(first, second);
        assertThat(attempts).containsExactly(first, second);
    }

    @Test
    void merge_overflow_leaves_a_valid_prefix_and_preserves_the_batch() {
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED).build();
        var maximum = DiagnosticBatch.of(List.of(warning),
                new DiagnosticSummary(Long.MAX_VALUE, Long.MAX_VALUE - 1,
                        Map.of(DiagnosticSeverity.WARN, Long.MAX_VALUE)), TestCodes.DIAGNOSTICS_SUPPRESSED);
        var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        run.add(warning);
        var delivered = new ArrayList<Diagnostic>();

        assertThatThrownBy(() -> run.addAll(maximum, delivered::add)).isInstanceOf(ArithmeticException.class);

        assertThat(run.summary().total()).isEqualTo(2);
        assertThat(run.summary().suppressed()).isOne();
        assertThat(run.batch().retained()).containsExactly(warning);
        assertThat(delivered).isEmpty();
        assertThat(maximum.summary().total()).isEqualTo(Long.MAX_VALUE);
    }

    private static List<Diagnostic> select(List<Diagnostic> input, String indexes) {
        return java.util.Arrays.stream(indexes.split(" ")).map(Integer::parseInt).map(input::get).toList();
    }

    @Test
    void repeated_display_snapshots_use_the_clock_without_adding_counts_or_deliveries() {
        var ticks = new java.util.concurrent.atomic.AtomicLong();
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(instant(), zone); }
            public Instant instant() { return Instant.ofEpochSecond(ticks.getAndIncrement()); }
        };
        var run = new BoundedNotification(1, new DiagnosticFactory(clock), TestCodes.DIAGNOSTICS_SUPPRESSED);
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED).build();
        var error = FACTORY.create(TestCodes.STAGE_FAILED).build();
        var delivered = new ArrayList<Diagnostic>();
        var stage = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        stage.addAll(List.of(warning, warning, error));
        run.addAll(stage.batch(), delivered::add);
        var counts = run.summary();
        var batch = run.batch();

        var first = run.diagnostics();
        var second = run.diagnostics();

        assertThat(first).containsExactlyElementsOf(second);
        assertThat(first.getLast().timestamp()).isEqualTo(Instant.EPOCH);
        assertThat(second.getLast().timestamp()).isEqualTo(Instant.EPOCH.plusSeconds(1));
        assertThat(first.getLast().severity()).isEqualTo(DiagnosticSeverity.WARN);
        assertThat(first.getLast().context()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "limit", 1, "suppressedCount", 2L, "suppressedBySeverity", Map.of("WARN", 2L)));
        assertThatThrownBy(() -> first.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) first.getLast().context().get("suppressedBySeverity")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(run.summary()).isEqualTo(counts);
        assertThat(run.batch()).isEqualTo(batch);
        assertThat(batch.retained()).containsExactly(error);
        assertThat(delivered).containsExactly(warning, error);
    }

    @Test
    void marker_validation_uses_consumer_code_equality() {
        record Code(String id) implements DiagnosticCode {
            public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
            public DiagnosticImpact impact() { return DiagnosticImpact.RUN; }
        }
        var marker = new Code("consumer/suppressed");
        var equalCode = FACTORY.create(new Code(marker.id())).build();
        var stage = new BoundedDiagnosticCollector(1, marker);
        var run = new BoundedNotification(1, FACTORY, marker);

        assertThatThrownBy(() -> stage.add(equalCode)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.add(equalCode)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiagnosticBatch.of(List.of(equalCode),
                DiagnosticSummary.empty().plusDiagnostics(List.of(equalCode)), marker))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stage.batch().summary().total()).isZero();
        assertThat(run.summary().total()).isZero();
    }

    @Test
    void all_bounded_entry_points_reject_null_before_accepting_an_occurrence() {
        var marker = TestCodes.DIAGNOSTICS_SUPPRESSED;
        var run = new BoundedNotification(1, FACTORY, marker);
        var stage = new BoundedDiagnosticCollector(1, marker);
        assertThatNullPointerException().isThrownBy(() -> new BoundedNotification(1, null, marker));
        assertThatNullPointerException().isThrownBy(() -> new BoundedNotification(1, FACTORY, null));
        assertThatNullPointerException().isThrownBy(() -> new BoundedDiagnosticCollector(1, null));
        assertThatNullPointerException().isThrownBy(() -> stage.add(null));
        assertThatNullPointerException().isThrownBy(() -> stage.addAll(null));
        assertThatNullPointerException().isThrownBy(() -> run.offer(null));
        assertThatNullPointerException().isThrownBy(() -> run.addAll((java.util.Collection<Diagnostic>) null));
        assertThatNullPointerException().isThrownBy(() -> run.addAll((DiagnosticBatch) null));
        assertThatNullPointerException().isThrownBy(() -> run.addAll(stage.batch(), null));
        assertThatNullPointerException().isThrownBy(() -> DiagnosticBatch.of(null, DiagnosticSummary.empty(), marker));
        assertThatNullPointerException().isThrownBy(() -> DiagnosticBatch.of(List.of(), null, marker));
        assertThatNullPointerException().isThrownBy(() -> DiagnosticBatch.of(List.of(), DiagnosticSummary.empty(), null));
        assertThatNullPointerException().isThrownBy(() -> DiagnosticBatch.of(java.util.Arrays.asList((Diagnostic) null),
                new DiagnosticSummary(1, 0, Map.of(DiagnosticSeverity.WARN, 1L)), marker));
        assertThat(run.summary()).isEqualTo(DiagnosticSummary.empty());
        assertThat(stage.batch().summary()).isEqualTo(DiagnosticSummary.empty());
    }

    @Test
    void invalid_marker_metadata_is_rejected_at_every_bounded_constructor() {
        record Code(String id, DiagnosticSeverity defaultSeverity, DiagnosticImpact impact) implements DiagnosticCode { }
        for (var code : List.of(new Code(null, DiagnosticSeverity.WARN, DiagnosticImpact.RUN),
                new Code("marker", null, DiagnosticImpact.RUN), new Code("marker", DiagnosticSeverity.WARN, null))) {
            assertThatNullPointerException().isThrownBy(() -> new BoundedNotification(1, FACTORY, code));
            assertThatNullPointerException().isThrownBy(() -> new BoundedDiagnosticCollector(1, code));
            assertThatNullPointerException().isThrownBy(() -> DiagnosticBatch.of(List.of(), DiagnosticSummary.empty(), code));
        }
        var blank = new Code("\t", DiagnosticSeverity.WARN, DiagnosticImpact.RUN);
        assertThatThrownBy(() -> new BoundedNotification(1, FACTORY, blank)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedDiagnosticCollector(1, blank)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiagnosticBatch.of(List.of(), DiagnosticSummary.empty(), blank))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partially_invalid_collections_keep_only_the_accepted_prefix() {
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED).build();
        var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var stage = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var values = java.util.Arrays.asList(warning, null, warning);

        assertThatNullPointerException().isThrownBy(() -> stage.addAll(values));
        assertThatNullPointerException().isThrownBy(() -> run.addAll(values));

        assertThat(stage.batch().retained()).containsExactly(warning);
        assertThat(run.batch().retained()).containsExactly(warning);
        assertThat(run.summary().total()).isOne();
        assertThat(stage.batch().summary()).isEqualTo(run.summary());
    }

    @Test
    void a_retained_error_cannot_hide_an_observed_fatal_but_fatal_can_represent_both() {
        var error = FACTORY.create(TestCodes.STAGE_FAILED).build();
        var fatal = FACTORY.create(TestCodes.READ_FAILED).build();
        var counts = new DiagnosticSummary(2, 1,
                Map.of(DiagnosticSeverity.ERROR, 1L, DiagnosticSeverity.FATAL, 1L));

        assertThatThrownBy(() -> DiagnosticBatch.of(List.of(error), counts, TestCodes.DIAGNOSTICS_SUPPRESSED))
                .isInstanceOf(IllegalArgumentException.class);
        var valid = DiagnosticBatch.of(List.of(fatal), counts, TestCodes.DIAGNOSTICS_SUPPRESSED);
        assertThat(valid.suppressedBySeverity()).containsExactlyEntriesOf(Map.of(DiagnosticSeverity.ERROR, 1L));
    }

    @Test
    void externally_constructed_batch_copies_detail_and_reconstructs_only_nonzero_omitted_counts() {
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED).build();
        var retained = new ArrayList<>(List.of(warning));
        var counts = new DiagnosticSummary(3, 2,
                Map.of(DiagnosticSeverity.WARN, 3L, DiagnosticSeverity.INFO, 0L));
        var batch = DiagnosticBatch.of(retained, counts, TestCodes.DIAGNOSTICS_SUPPRESSED);

        retained.clear();

        assertThat(batch.retained()).containsExactly(warning);
        assertThat(batch.summary()).isEqualTo(counts);
        assertThat(batch.suppressedBySeverity()).containsExactlyEntriesOf(Map.of(DiagnosticSeverity.WARN, 2L));
        assertThatThrownBy(() -> batch.retained().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> batch.suppressedBySeverity().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void batch_value_is_preserved_across_snapshots_and_marker_configuration() {
        var occurrence = FACTORY.create(TestCodes.ITEM_SKIPPED).with("row", 1).build();
        var summary = DiagnosticSummary.empty().plusDiagnostics(java.util.List.of(occurrence));
        var first = DiagnosticBatch.of(java.util.List.of(occurrence), summary, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var sameValue = DiagnosticBatch.of(java.util.List.of(occurrence), summary, TestCodes.READ_FAILED);
        assertThat(new java.util.HashSet<>(java.util.List.of(first, sameValue))).hasSize(1);
        assertThat(first).isEqualTo(first).isNotEqualTo(null).isNotEqualTo(occurrence);
        var differentSample = FACTORY.create(TestCodes.ITEM_SKIPPED).with("row", 2).build();
        assertThat(first).isNotEqualTo(DiagnosticBatch.of(java.util.List.of(differentSample), summary,
                TestCodes.DIAGNOSTICS_SUPPRESSED));
        assertThat(first).isNotEqualTo(DiagnosticBatch.of(java.util.List.of(occurrence),
                new DiagnosticSummary(2, 1, java.util.Map.of(DiagnosticSeverity.WARN, 2L)),
                TestCodes.DIAGNOSTICS_SUPPRESSED));
    }

    @Test
    void rejects_invalid_budget_and_marker_configuration_at_construction() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new BoundedNotification(0, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED));
        record Marker(String id) implements com.iocextractor.platform.diagnostics.DiagnosticCode {
            public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
            public com.iocextractor.platform.diagnostics.DiagnosticImpact impact() {
                return com.iocextractor.platform.diagnostics.DiagnosticImpact.RUN;
            }
        }
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new BoundedDiagnosticCollector(1, new Marker(" ")));
    }

    @Test
    void rejects_its_marker_before_counting_or_delivering_any_batch_detail() {
        var marker = FACTORY.create(TestCodes.DIAGNOSTICS_SUPPRESSED).build();
        var ordinary = FACTORY.create(TestCodes.ITEM_SKIPPED).build();
        var run = new BoundedNotification(2, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var collector = new BoundedDiagnosticCollector(2, TestCodes.DIAGNOSTICS_SUPPRESSED);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> run.offer(marker));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> collector.add(marker));
        var foreignBatch = DiagnosticBatch.of(java.util.List.of(ordinary, marker),
                DiagnosticSummary.empty().plusDiagnostics(java.util.List.of(ordinary, marker)), TestCodes.READ_FAILED);
        var deliveries = new java.util.ArrayList<Diagnostic>();
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> run.addAll(foreignBatch, deliveries::add));
        assertThat(run.summary().total()).isZero();
        assertThat(collector.batch().summary().total()).isZero();
        assertThat(deliveries).isEmpty();
    }

    @Test
    void rejects_overflow_when_input_arrives_without_breaking_the_existing_snapshot() {
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED).build();
        var maximum = DiagnosticBatch.of(java.util.List.of(warning),
                new DiagnosticSummary(Long.MAX_VALUE, Long.MAX_VALUE - 1,
                        java.util.Map.of(DiagnosticSeverity.WARN, Long.MAX_VALUE)), TestCodes.DIAGNOSTICS_SUPPRESSED);
        for (var next : java.util.List.of(warning, FACTORY.create(TestCodes.SOURCE_UNREADABLE).build())) {
            var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
            run.addAll(maximum);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> run.offer(next))
                    .isInstanceOf(ArithmeticException.class);
            assertThat(run.summary().total()).isEqualTo(Long.MAX_VALUE);
            assertThat(run.summary().suppressed()).isEqualTo(Long.MAX_VALUE - 1);
        }
    }

    @Test
    void caps_retained_diagnostics_and_reports_suppression() {
        var notification = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);

        notification.add(FACTORY.create(TestCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(TestCodes.ITEM_SKIPPED)
                .with("item", "two").with("stage", "test").with("reason", "bad").build());

        assertThat(notification.diagnostics()).extracting(diagnostic -> diagnostic.code().id())
                .containsExactly("TEST.ITEM_SKIPPED", "TEST.DIAGNOSTICS_SUPPRESSED");
        assertThat(notification.summary().suppressed()).isOne();
    }

    @Test
    void first_rejecting_diagnostic_is_never_hidden_by_budget() {
        var notification = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        notification.add(FACTORY.create(TestCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(TestCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "failed").build());

        assertThat(notification.diagnostics().getFirst().code()).isEqualTo(TestCodes.STAGE_FAILED);
        assertThat(notification.summary().hasErrors()).isTrue();
    }

    @Test
    void fatal_supersedes_retained_error_for_collect_and_continue_policy() {
        var notification = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        notification.add(FACTORY.create(TestCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "error").build());
        notification.add(FACTORY.create(TestCodes.STAGE_FAILED)
                .severity(DiagnosticSeverity.FATAL)
                .with("stage", "test").with("reason", "fatal").build());

        assertThat(notification.diagnostics().getFirst().severity())
                .isEqualTo(DiagnosticSeverity.FATAL);
    }

    @Test
    void operation_diagnostic_stays_visible_without_consuming_or_being_displaced_by_budget() {
        var notification = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        var operationWarning = FACTORY.create(TestCodes.SOURCE_UNREADABLE)
                .severity(DiagnosticSeverity.WARN)
                .with("source", "source-1")
                .with("reason", "lossy projection")
                .build();
        notification.add(operationWarning);
        notification.add(FACTORY.create(TestCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(TestCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "failed").build());

        assertThat(notification.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .containsExactly(
                        "TEST.SOURCE_UNREADABLE",
                        "TEST.STAGE_FAILED",
                        "TEST.DIAGNOSTICS_SUPPRESSED");
        assertThat(notification.summary().total()).isEqualTo(3);
        assertThat(notification.summary().suppressed()).isOne();
    }

    @Test
    void construction_bound_preserves_exact_counts_and_late_rejecting_signals() {
        var collector = new BoundedDiagnosticCollector(3, TestCodes.DIAGNOSTICS_SUPPRESSED);
        for (int index = 0; index < 100_000; index++) {
            collector.add(FACTORY.create(TestCodes.ITEM_SKIPPED)
                    .with("item", index).with("stage", "test").with("reason", "bad").build());
        }
        collector.add(FACTORY.create(TestCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "late error").build());
        collector.add(FACTORY.create(TestCodes.STAGE_FAILED)
                .severity(DiagnosticSeverity.FATAL)
                .with("stage", "test").with("reason", "late fatal").build());

        var batch = collector.batch();
        assertThat(batch.retained()).extracting(diagnostic -> diagnostic.context().get("item"))
                .containsExactly(0, 1, 2, null, null);
        assertThat(batch.summary().total()).isEqualTo(100_002);
        assertThat(batch.summary().suppressed()).isEqualTo(99_997);
        assertThat(batch.summary().count(DiagnosticSeverity.ERROR)).isOne();
        assertThat(batch.summary().count(DiagnosticSeverity.FATAL)).isOne();
        var run = new BoundedNotification(3, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        run.addAll(batch);
        assertThat(run.summary().total()).isEqualTo(100_002);
        assertThat(run.summary().suppressed()).isEqualTo(99_999);
        assertThat(run.diagnostics()).hasSize(4);
    }

    @Test
    void merging_stage_batches_matches_direct_stream_samples_counts_and_delivery() {
        var random = new java.util.Random(302_033L);
        for (int limit : new int[]{1, 3, 128}) {
            var direct = new BoundedNotification(limit, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
            var merged = new BoundedNotification(limit, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
            var directDelivery = new java.util.ArrayList<com.iocextractor.platform.diagnostics.Diagnostic>();
            var mergedDelivery = new java.util.ArrayList<com.iocextractor.platform.diagnostics.Diagnostic>();
            for (int stage = 0; stage < 4; stage++) {
                var collector = new BoundedDiagnosticCollector(limit, TestCodes.DIAGNOSTICS_SUPPRESSED);
                for (int item = 0; item < 1_000; item++) {
                    var severity = com.iocextractor.platform.diagnostics.DiagnosticSeverity.values()[random.nextInt(6)];
                    var code = item % 100 == 0 ? TestCodes.SOURCE_UNREADABLE
                            : TestCodes.STAGE_FAILED;
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
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build();
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                DiagnosticBatch.of(java.util.List.of(warning), DiagnosticSummary.empty(), TestCodes.DIAGNOSTICS_SUPPRESSED));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                DiagnosticBatch.of(java.util.List.of(), new DiagnosticSummary(1, 1,
                        java.util.Map.of(DiagnosticSeverity.ERROR, 1L)), TestCodes.DIAGNOSTICS_SUPPRESSED));
        var collector = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        collector.add(warning);
        var failure = new IllegalStateException("collector unavailable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED).addAll(collector.batch(), diagnostic -> { throw failure; }))
                .isSameAs(failure);
    }

    @Test
    void batch_snapshots_are_immutable_and_exclude_the_synthetic_summary() {
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build();
        var collector = new BoundedDiagnosticCollector(1, TestCodes.DIAGNOSTICS_SUPPRESSED);
        collector.addAll(java.util.List.of(warning, warning));
        var snapshot = collector.batch();
        collector.add(warning);
        assertThat(snapshot.summary().total()).isEqualTo(2);
        assertThat(collector.batch().summary().total()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.retained().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        run.addAll(java.util.List.of(warning));
        assertThat(run.diagnostics()).containsExactly(warning);
        run.addAll(snapshot);
        assertThat(run.batch().retained()).containsExactly(warning);
        assertThat(run.batch().summary().total()).isEqualTo(3);
        assertThat(run.batch().summary().suppressed()).isEqualTo(2);
        assertThat(run.diagnostics()).hasSize(2);
    }

    @Test
    void invalid_local_budget_severity_and_synthetic_batches_are_rejected() {
        var warning = FACTORY.create(TestCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build();
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new BoundedDiagnosticCollector(0, TestCodes.DIAGNOSTICS_SUPPRESSED));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                DiagnosticBatch.of(java.util.List.of(warning),
                        new DiagnosticSummary(1, 0, java.util.Map.of(DiagnosticSeverity.INFO, 1L)), TestCodes.DIAGNOSTICS_SUPPRESSED));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                DiagnosticBatch.of(java.util.List.of(warning), new DiagnosticSummary(2, 1,
                        java.util.Map.of(DiagnosticSeverity.WARN, 1L, DiagnosticSeverity.FATAL, 1L)), TestCodes.DIAGNOSTICS_SUPPRESSED));
        var run = new BoundedNotification(1, FACTORY, TestCodes.DIAGNOSTICS_SUPPRESSED);
        run.addAll(java.util.List.of(warning, warning));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                DiagnosticBatch.of(run.diagnostics(),
                        DiagnosticSummary.empty().plusDiagnostics(run.diagnostics()), TestCodes.DIAGNOSTICS_SUPPRESSED));
    }
}
