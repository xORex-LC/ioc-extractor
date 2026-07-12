package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;

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

    /** Builds a summary from the bounded envelope representation. */
    public static DiagnosticSummary from(List<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        long suppressed = diagnostics.stream()
                .filter(diagnostic -> diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)
                .mapToLong(diagnostic -> ((Number) diagnostic.context().get("suppressedCount")).longValue())
                .sum();
        var retained = diagnostics.stream()
                .filter(diagnostic -> diagnostic.code() != PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)
                .toList();
        var summary = diagnostics.stream()
                .filter(diagnostic -> diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)
                .findFirst();
        if (summary.isEmpty()) {
            return of(retained, Map.of());
        }
        var rawCounts = summary.orElseThrow().context().get("suppressedBySeverity");
        if (!(rawCounts instanceof Map<?, ?> counts)) {
            return of(retained, Map.of(summary.orElseThrow().severity(), suppressed));
        }
        var bySeverity = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        counts.forEach((severity, count) -> bySeverity.put(
                DiagnosticSeverity.valueOf(severity.toString()), ((Number) count).longValue()));
        return of(retained, bySeverity);
    }

    /** Returns whether the run observed an error or fatal diagnostic. */
    public boolean hasErrors() {
        return bySeverity.getOrDefault(DiagnosticSeverity.ERROR, 0L) > 0
                || bySeverity.getOrDefault(DiagnosticSeverity.FATAL, 0L) > 0;
    }
}
