package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;

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
    private final List<Diagnostic> retained = new ArrayList<>();
    private final Map<DiagnosticSeverity, Long> suppressed = new EnumMap<>(DiagnosticSeverity.class);
    private int budgeted;
    private boolean error;
    private boolean fatal;

    public BoundedDiagnosticCollector(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    /** Records one occurrence without building an unbounded detail list. */
    public void add(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
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
            suppressed.merge(diagnostic.severity(), 1L, Long::sum);
        }
        error |= diagnostic.severity().isErrorOrWorse();
        fatal |= diagnostic.severity() == DiagnosticSeverity.FATAL;
    }

    /** Records a bounded per-item result in its encounter order. */
    public void addAll(Collection<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics").forEach(this::add);
    }

    /** Returns retained detail and exact counts; no suppression-summary occurrence is created. */
    public DiagnosticBatch batch() {
        return new DiagnosticBatch(retained, DiagnosticSummary.of(retained, suppressed));
    }
}
