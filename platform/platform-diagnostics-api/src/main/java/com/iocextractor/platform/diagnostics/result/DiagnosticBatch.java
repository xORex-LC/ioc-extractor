package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.DiagnosticCode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable retained occurrences paired with exact observed counts.
 * A batch never includes its synthetic suppression marker. The marker supplied
 * to {@link #of} is validation configuration, not part of the batch value.
 * Context values and causes inside retained diagnostics are not deep-copied.
 */
public final class DiagnosticBatch {
    private final List<Diagnostic> retained;
    private final DiagnosticSummary summary;

    private DiagnosticBatch(List<Diagnostic> retained, DiagnosticSummary summary, DiagnosticCode suppressionCode) {
        this.retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
        this.summary = Objects.requireNonNull(summary, "summary");
        retained = this.retained;
        SuppressionCodes.requireValid(suppressionCode);
        if (retained.size() != summary.total() - summary.suppressed()
                || retained.stream().anyMatch(diagnostic ->
                diagnostic.code().equals(suppressionCode))) {
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

    /**
     * Validates retained counts, severity counts and rejecting signals.
     *
     * @param retained occurrences in encounter order; copied, with no null elements
     * @param summary exact counts, including omitted occurrences
     * @param suppressionCode reserved synthetic marker shared by the aggregation tree
     * @return an immutable batch
     * @throws NullPointerException if an argument or retained element is null
     * @throws IllegalArgumentException if metadata or counts are inconsistent, a marker
     *         is retained, or an observed ERROR/FATAL has no retained rejecting signal
     */
    public static DiagnosticBatch of(List<Diagnostic> retained, DiagnosticSummary summary,
                                     DiagnosticCode suppressionCode) {
        return new DiagnosticBatch(retained, summary, suppressionCode);
    }

    /** Exposes the retained sample in encounter order.
     * @return retained occurrences in encounter order, with no synthetic marker */
    public List<Diagnostic> retained() {
        return retained;
    }

    /** Exposes counts independently of the retained sample.
     * @return exact observed counts, including suppressed occurrences */
    public DiagnosticSummary summary() {
        return summary;
    }

    /**
     * Compares retained occurrences and summary; the validation marker is excluded.
     * @param other candidate batch
     * @return whether both value components are equal
     */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DiagnosticBatch that
                && retained.equals(that.retained) && summary.equals(that.summary);
    }

    /** Hashes the same components used by equality.
     * @return a hash of retained occurrences and summary */
    @Override
    public int hashCode() {
        return Objects.hash(retained, summary);
    }

    /** Formats the retained sample and its counts for inspection.
     * @return retained occurrences and their exact summary */
    @Override
    public String toString() {
        return "DiagnosticBatch[retained=" + retained + ", summary=" + summary + "]";
    }

    /**
     * Subtracts retained severity counts from the observed totals.
     * @return immutable nonzero counts of omitted occurrences by severity
     */
    public Map<DiagnosticSeverity, Long> suppressedBySeverity() {
        var counts = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        counts.putAll(summary.bySeverity());
        retained.forEach(diagnostic -> counts.merge(diagnostic.severity(), -1L, Long::sum));
        counts.values().removeIf(count -> count == 0);
        return Map.copyOf(counts);
    }
}
