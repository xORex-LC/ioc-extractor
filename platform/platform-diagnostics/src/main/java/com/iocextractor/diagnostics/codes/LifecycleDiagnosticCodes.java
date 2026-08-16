package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/** Stable diagnostics for canonical record lifecycle runtime safety. */
public enum LifecycleDiagnosticCodes implements DiagnosticCode {
    ADMISSION_FAILED(DiagnosticSeverity.FATAL, "lifecycle.admission-failed",
            "Canonical lifecycle admission failed: {reason}"),
    CLOCK_UNSAFE(DiagnosticSeverity.FATAL, "lifecycle.clock-unsafe",
            "System UTC clock cannot establish a safe lifecycle time: {reason}"),
    RECONCILIATION_FAILED(DiagnosticSeverity.ERROR, "lifecycle.reconciliation-failed",
            "Canonical expiration reconciliation failed: {reason}"),
    PROJECTION_FAILED(DiagnosticSeverity.ERROR, "lifecycle.projection-failed",
            "Mutable artifact projection convergence failed: {reason}"),
    HISTORY_RETENTION_FAILED(DiagnosticSeverity.ERROR, "lifecycle.history-retention-failed",
            "Lifecycle history retention failed: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    LifecycleDiagnosticCodes(DiagnosticSeverity defaultSeverity,
                             String messageKey,
                             String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "LIFECYCLE." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.LIFECYCLE;
    }

    @Override
    public DiagnosticSeverity defaultSeverity() {
        return defaultSeverity;
    }

    @Override
    public DiagnosticImpact impact() {
        return DiagnosticImpact.OPERATION;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }

    @Override
    public String defaultMessageTemplate() {
        return defaultMessageTemplate;
    }
}
