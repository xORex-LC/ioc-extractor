package com.iocextractor.application.pipeline;

import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.result.DiagnosticSummary;

import java.util.Objects;

/** Processing completion independent of driving-adapter exit semantics. */
public enum CompletionStatus {
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    COMPLETED_WITH_ERRORS;

    /** Derives completion from the full diagnostic summary. */
    public static CompletionStatus from(DiagnosticSummary summary) {
        Objects.requireNonNull(summary, "summary");
        if (summary.hasErrors()) {
            return COMPLETED_WITH_ERRORS;
        }
        if (summary.bySeverity().getOrDefault(DiagnosticSeverity.WARN, 0L) > 0) {
            return COMPLETED_WITH_WARNINGS;
        }
        return COMPLETED;
    }
}
