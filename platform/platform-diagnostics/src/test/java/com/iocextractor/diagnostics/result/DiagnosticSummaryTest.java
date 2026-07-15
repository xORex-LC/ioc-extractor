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
}
