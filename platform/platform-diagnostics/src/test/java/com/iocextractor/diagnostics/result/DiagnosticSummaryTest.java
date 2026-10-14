package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
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
    void extends_counts_without_changing_suppression() {
        var summary = new DiagnosticSummary(3, 2, Map.of(
                DiagnosticSeverity.DEBUG, 2L,
                DiagnosticSeverity.WARN, 1L));
        var operationWarning = FACTORY.create(IngestDiagnosticCodes.SOURCE_UNREADABLE)
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
