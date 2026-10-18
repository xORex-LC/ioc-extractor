package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.TestCodes;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DiagnosticSummaryTest {

    private static final DiagnosticFactory FACTORY = new DiagnosticFactory(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void overflow_in_retained_extension_is_rejected_without_changing_the_source_counts() {
        var maximum = new DiagnosticSummary(Long.MAX_VALUE, Long.MAX_VALUE - 1,
                Map.of(DiagnosticSeverity.WARN, Long.MAX_VALUE));
        for (var severity : DiagnosticSeverity.values()) {
            var extra = FACTORY.create(TestCodes.STAGE_FAILED).severity(severity).build();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> maximum.plusDiagnostics(java.util.List.of(extra)))
                    .isInstanceOf(ArithmeticException.class);
        }
        assertThat(maximum.total()).isEqualTo(Long.MAX_VALUE);
        assertThat(maximum.suppressed()).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(maximum.bySeverity()).containsExactlyEntriesOf(Map.of(DiagnosticSeverity.WARN, Long.MAX_VALUE));
    }

    @Test
    void summary_copies_its_map_and_rejects_null_inputs() {
        var counts = new java.util.HashMap<DiagnosticSeverity, Long>();
        counts.put(DiagnosticSeverity.WARN, 1L);
        var summary = new DiagnosticSummary(1, 0, counts);
        counts.clear();
        assertThat(summary.count(DiagnosticSeverity.WARN)).isOne();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> summary.bySeverity().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticSummary(0, 0, null));
        counts.put(null, 0L);
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticSummary(0, 0, counts));
        counts.clear();
        counts.put(DiagnosticSeverity.WARN, null);
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticSummary(0, 0, counts));
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> summary.plus(null));
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> summary.since(null));
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> summary.count(null));
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> summary.plusDiagnostics(null));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> summary.plusDiagnostics(java.util.Arrays.asList((com.iocextractor.platform.diagnostics.Diagnostic) null)));
    }

    @Test
    void zero_entries_are_valid_and_part_of_summary_value_equality() {
        var explicitZero = new DiagnosticSummary(0, 0, Map.of(DiagnosticSeverity.ERROR, 0L));
        assertThat(explicitZero.hasErrors()).isFalse();
        assertThat(explicitZero.count(DiagnosticSeverity.FATAL)).isZero();
        assertThat(explicitZero).isNotEqualTo(DiagnosticSummary.empty());
        assertThat(explicitZero.plus(DiagnosticSummary.empty())).isEqualTo(explicitZero);
    }

    @Test
    void rejects_negative_totals_suppression_and_severity_counts() {
        for (var counts : java.util.List.of(new long[]{-1, 0}, new long[]{0, -1}, new long[]{0, 1})) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new DiagnosticSummary(counts[0], counts[1], Map.of()));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DiagnosticSummary(0, 0, Map.of(DiagnosticSeverity.WARN, -1L)));
    }

    @Test
    void rejects_long_overflow_in_constructor_and_summary_arithmetic() {
        var maximum = new DiagnosticSummary(Long.MAX_VALUE, 0,
                java.util.Map.of(DiagnosticSeverity.WARN, Long.MAX_VALUE));
        var one = new DiagnosticSummary(1, 0, java.util.Map.of(DiagnosticSeverity.WARN, 1L));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> maximum.plus(one))
                .isInstanceOf(ArithmeticException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DiagnosticSummary(Long.MAX_VALUE, 0,
                java.util.Map.of(DiagnosticSeverity.WARN, Long.MAX_VALUE, DiagnosticSeverity.ERROR, 1L)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void extends_counts_without_changing_suppression() {
        var summary = new DiagnosticSummary(3, 2, Map.of(
                DiagnosticSeverity.DEBUG, 2L,
                DiagnosticSeverity.WARN, 1L));
        var operationWarning = FACTORY.create(TestCodes.SOURCE_UNREADABLE)
                .severity(DiagnosticSeverity.WARN)
                .with("source", "source-1")
                .with("reason", "lossy projection")
                .build();

        var extended = summary.plusDiagnostics(java.util.List.of(operationWarning));

        assertThat(extended.total()).isEqualTo(4);
        assertThat(extended.suppressed()).isEqualTo(2);
        assertThat(extended.count(DiagnosticSeverity.DEBUG)).isEqualTo(2);
        assertThat(extended.count(DiagnosticSeverity.WARN)).isEqualTo(2);
    }

    @Test
    void returns_same_value_for_empty_extension() {
        var summary = DiagnosticSummary.empty();

        assertThat(summary.plusDiagnostics(java.util.List.of())).isSameAs(summary);
    }

    @Test
    void combines_stage_totals_and_recovers_the_exact_delta() {
        var previous = new DiagnosticSummary(10, 8, Map.of(DiagnosticSeverity.WARN, 10L));
        var stage = new DiagnosticSummary(100, 97, Map.of(
                DiagnosticSeverity.WARN, 99L, DiagnosticSeverity.ERROR, 1L));

        var combined = previous.plus(stage);

        assertThat(combined.total()).isEqualTo(110);
        assertThat(combined.suppressed()).isEqualTo(105);
        assertThat(combined.bySeverity()).containsExactlyInAnyOrderEntriesOf(Map.of(
                DiagnosticSeverity.WARN, 109L, DiagnosticSeverity.ERROR, 1L));
        assertThat(combined.since(previous)).isEqualTo(stage);
        var emptyDelta = combined.since(combined);
        assertThat(emptyDelta.total()).isZero();
        assertThat(emptyDelta.suppressed()).isZero();
        assertThat(emptyDelta.bySeverity().values()).allMatch(count -> count == 0);
        assertThat(combined.since(DiagnosticSummary.empty())).isEqualTo(combined);
    }

    @Test
    void rejects_removal_of_observed_counts_even_when_the_total_increases() {
        var previous = new DiagnosticSummary(1, 1, Map.of(DiagnosticSeverity.ERROR, 1L));
        var fewerErrors = new DiagnosticSummary(2, 1, Map.of(DiagnosticSeverity.WARN, 2L));

        assertThatIllegalArgumentException().isThrownBy(() -> fewerErrors.since(previous));
        assertThatIllegalArgumentException().isThrownBy(() -> DiagnosticSummary.empty().since(previous));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DiagnosticSummary(1, 0, Map.of(DiagnosticSeverity.WARN, 2L)));
    }
}
