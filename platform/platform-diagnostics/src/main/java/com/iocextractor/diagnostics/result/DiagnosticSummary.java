package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticSeverity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable aggregate of diagnostics observed during one processing run. */
public record DiagnosticSummary(long total,
                                long suppressed,
                                Map<DiagnosticSeverity, Long> bySeverity) {

    public DiagnosticSummary {
        if (total < 0 || suppressed < 0 || suppressed > total) {
            throw new IllegalArgumentException("Invalid diagnostic counts");
        }
        bySeverity = Map.copyOf(Objects.requireNonNull(bySeverity, "bySeverity"));
        if (bySeverity.values().stream().anyMatch(count -> count < 0)
                || bySeverity.values().stream().mapToLong(Long::longValue).sum() != total) {
            throw new IllegalArgumentException("Severity counts must be non-negative and sum to total");
        }
    }

    /** Returns an empty summary. */
    public static DiagnosticSummary empty() {
        return new DiagnosticSummary(0, 0, Map.of());
    }

    static DiagnosticSummary of(List<Diagnostic> diagnostics,
                                Map<DiagnosticSeverity, Long> suppressedBySeverity) {
        var counts = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        diagnostics.forEach(diagnostic -> counts.merge(diagnostic.severity(), 1L, Long::sum));
        suppressedBySeverity.forEach((severity, count) -> counts.merge(severity, count, Long::sum));
        long suppressed = suppressedBySeverity.values().stream().mapToLong(Long::longValue).sum();
        return new DiagnosticSummary(diagnostics.size() + suppressed, suppressed, counts);
    }

    /** Returns whether the run observed an error or fatal diagnostic. */
    public boolean hasErrors() {
        return bySeverity.getOrDefault(DiagnosticSeverity.ERROR, 0L) > 0
                || bySeverity.getOrDefault(DiagnosticSeverity.FATAL, 0L) > 0;
    }
}
