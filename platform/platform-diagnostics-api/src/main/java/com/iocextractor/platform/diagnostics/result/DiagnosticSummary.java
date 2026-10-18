package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact occurrence counts, independent of how much detail was retained.
 * Zero severity entries are allowed; absent severities also count as zero.
 * The map is copied. Generated record equality includes its exact map contents.
 * Checked arithmetic throws {@link ArithmeticException} instead of wrapping.
 *
 * @param total observed occurrences, excluding synthetic markers
 * @param suppressed occurrences whose details were omitted
 * @param bySeverity non-null counts summing to total; keys and values must be non-null
 */
public record DiagnosticSummary(long total,
                                long suppressed,
                                Map<DiagnosticSeverity, Long> bySeverity) {

    /**
     * Validates counts and copies the severity map.
     * @param total non-negative observed count
     * @param suppressed between zero and total, inclusive
     * @param bySeverity non-negative severity counts that sum to total
     * @throws NullPointerException if map, key or value is null
     * @throws IllegalArgumentException if counts are negative or inconsistent
     * @throws ArithmeticException if the sum overflows a long
     */
    public DiagnosticSummary {
        if (total < 0 || suppressed < 0 || suppressed > total) {
            throw new IllegalArgumentException("Invalid diagnostic counts");
        }
        bySeverity = Map.copyOf(Objects.requireNonNull(bySeverity, "bySeverity"));
        if (bySeverity.values().stream().anyMatch(count -> count < 0)
                || bySeverity.values().stream().reduce(0L, Math::addExact) != total) {
            throw new IllegalArgumentException("Severity counts must be non-negative and sum to total");
        }
    }

    /** Creates a summary with no observed occurrences.
     * @return zero observed and suppressed occurrences */
    public static DiagnosticSummary empty() {
        return new DiagnosticSummary(0, 0, Map.of());
    }

    /**
     * Combines independent occurrence counts; no deduplication occurs.
     * @param additional non-null counts of another batch
     * @return their sum, leaving both inputs unchanged
     * @throws ArithmeticException if a count exceeds long range
     */
    public DiagnosticSummary plus(DiagnosticSummary additional) {
        Objects.requireNonNull(additional, "additional");
        var counts = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        counts.putAll(bySeverity);
        additional.bySeverity().forEach((severity, count) -> counts.merge(severity, count, Math::addExact));
        return new DiagnosticSummary(Math.addExact(total, additional.total()), Math.addExact(suppressed, additional.suppressed()), counts);
    }

    /**
     * Subtracts an earlier snapshot of the same accumulation.
     * @param previous non-null earlier counts
     * @return the exact count delta
     * @throws IllegalArgumentException if any count would decrease or become inconsistent
     */
    public DiagnosticSummary since(DiagnosticSummary previous) {
        Objects.requireNonNull(previous, "previous");
        var counts = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        counts.putAll(bySeverity);
        previous.bySeverity().forEach((severity, count) -> counts.merge(severity, -count, Math::addExact));
        return new DiagnosticSummary(total - previous.total(), suppressed - previous.suppressed(), counts);
    }

    /**
     * Returns this summary extended with retained terminal diagnostics.
     *
     * <p>The appended diagnostics are not suppression artifacts, so the
     * suppression count is preserved. The caller must exclude synthetic markers.</p>
     * @param diagnostics non-null occurrences, with no null elements
     * @return extended immutable counts, or this instance for empty input
     * @throws ArithmeticException if a count exceeds long range
     */
    public DiagnosticSummary plusDiagnostics(Collection<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (diagnostics.isEmpty()) {
            return this;
        }
        var counts = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        counts.putAll(bySeverity);
        diagnostics.forEach(diagnostic -> counts.merge(
                Objects.requireNonNull(diagnostic, "diagnostic").severity(), 1L, Math::addExact));
        return new DiagnosticSummary(Math.addExact(total, diagnostics.size()), suppressed, counts);
    }

    static DiagnosticSummary of(List<Diagnostic> diagnostics,
                                Map<DiagnosticSeverity, Long> suppressedBySeverity) {
        var counts = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        diagnostics.forEach(diagnostic -> counts.merge(diagnostic.severity(), 1L, Math::addExact));
        suppressedBySeverity.forEach((severity, count) -> counts.merge(severity, count, Math::addExact));
        long suppressed = suppressedBySeverity.values().stream().reduce(0L, Math::addExact);
        return new DiagnosticSummary(Math.addExact(diagnostics.size(), suppressed), suppressed, counts);
    }

    /** Checks the complete observed counts, including omitted detail.
     * @return whether any observed occurrence was ERROR or FATAL */
    public boolean hasErrors() {
        return count(DiagnosticSeverity.ERROR) > 0 || count(DiagnosticSeverity.FATAL) > 0;
    }

    /**
     * Reads one severity count; missing severities count as zero.
     * @param severity non-null severity to inspect
     * @return observed count, or zero when absent
     */
    public long count(DiagnosticSeverity severity) {
        return bySeverity.getOrDefault(Objects.requireNonNull(severity, "severity"), 0L);
    }
}
