package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticCode;
import com.iocextractor.platform.diagnostics.DiagnosticImpact;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage-local construction bound. Retains the first budgeted occurrences plus
 * the first error and fatal, in encounter order, so merging into a run budget
 * preserves its original samples and rejecting signal. OPERATION occurrences
 * remain exempt, as in the run-level contract.
 *
 * <p>Each synchronous stage invocation owns its collector; it is not shared
 * between runs or threads. No suppressed detail is retained.</p>
 */
public final class BoundedDiagnosticCollector {
    private final int limit;
    private long observed;
    private final DiagnosticCode suppressionCode;
    private final List<Diagnostic> retained = new ArrayList<>();
    private final Map<DiagnosticSeverity, Long> suppressed = new EnumMap<>(DiagnosticSeverity.class);
    private int budgeted;
    private boolean error;
    private boolean fatal;

    /**
     * Creates a stage-local collector. At most limit plus two ELEMENT/RUN
     * samples are retained; OPERATION occurrences have no bound.
     * @param limit positive detail budget shared with the destination run accumulator
     * @param suppressionCode non-null valid code reserved throughout the aggregation tree
     * @throws IllegalArgumentException if limit is not positive or code ID is blank
     * @throws NullPointerException if code or required code metadata is null
     */
    public BoundedDiagnosticCollector(int limit, DiagnosticCode suppressionCode) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.suppressionCode = SuppressionCodes.requireValid(suppressionCode);
        this.limit = limit;
    }

    /**
     * Records one occurrence, counting duplicates separately.
     * @param diagnostic non-null occurrence
     * @throws IllegalArgumentException if its code equals the configured marker
     * @throws ArithmeticException if the total observed count exceeds long range
     */
    public void add(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (diagnostic.code().equals(suppressionCode)) {
            throw new IllegalArgumentException("Synthetic suppression markers are not occurrences");
        }
        observed = Math.incrementExact(observed);
        boolean firstError = diagnostic.severity().isErrorOrWorse() && !error;
        boolean firstFatal = diagnostic.severity() == DiagnosticSeverity.FATAL && !fatal;
        if (diagnostic.code().impact() == DiagnosticImpact.OPERATION) {
            retained.add(diagnostic);
        } else if (budgeted < limit) {
            retained.add(diagnostic);
            budgeted++;
        } else if (firstError || firstFatal) {
            retained.add(diagnostic);
        } else {
            suppressed.merge(diagnostic.severity(), 1L, Math::addExact);
        }
        error |= diagnostic.severity().isErrorOrWorse();
        fatal |= diagnostic.severity() == DiagnosticSeverity.FATAL;
    }

    /**
     * Records occurrences in encounter order. Earlier additions remain if a later one fails.
     * @param diagnostics non-null collection without null elements or synthetic markers
     */
    public void addAll(Collection<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics").forEach(this::add);
    }

    /**
     * Snapshots the stage sample for a later run-level merge.
     * @return immutable retained samples and exact counts, without a synthetic marker
     */
    public DiagnosticBatch batch() {
        return DiagnosticBatch.of(retained, DiagnosticSummary.of(retained, suppressed), suppressionCode);
    }
}
