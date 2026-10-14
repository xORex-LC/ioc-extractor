package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable retained occurrences and exact totals; synthetic summaries are not occurrences. */
public record DiagnosticBatch(List<Diagnostic> retained, DiagnosticSummary summary) {

    public DiagnosticBatch {
        retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
        Objects.requireNonNull(summary, "summary");
        if (retained.size() != summary.total() - summary.suppressed()
                || retained.stream().anyMatch(diagnostic ->
                diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)) {
            throw new IllegalArgumentException("Retained diagnostics must match the observed summary");
        }
        var represented = DiagnosticSummary.empty().plusDiagnostics(retained);
        for (DiagnosticSeverity severity : DiagnosticSeverity.values()) {
            if (represented.count(severity) > summary.count(severity)) {
                throw new IllegalArgumentException("Retained severity counts exceed observed counts");
            }
        }
        if (summary.hasErrors() && retained.stream().noneMatch(diagnostic -> diagnostic.severity().isErrorOrWorse())
                || summary.count(DiagnosticSeverity.FATAL) > 0 && retained.stream().noneMatch(diagnostic ->
                diagnostic.severity() == DiagnosticSeverity.FATAL)) {
            throw new IllegalArgumentException("A bounded batch must retain its rejecting signal");
        }
    }

    /** Returns exact suppressed counts without requiring suppressed detail in memory. */
    public Map<DiagnosticSeverity, Long> suppressedBySeverity() {
        var counts = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        counts.putAll(summary.bySeverity());
        retained.forEach(diagnostic -> counts.merge(diagnostic.severity(), -1L, Long::sum));
        counts.values().removeIf(count -> count == 0);
        return Map.copyOf(counts);
    }
}
