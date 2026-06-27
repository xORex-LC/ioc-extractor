package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/** Diagnostic codes produced by immutable artifact slice formation. */
public enum ExportDiagnosticCodes implements DiagnosticCode {
    SNAPSHOT_READ_FAILED(DiagnosticSeverity.ERROR, "export.snapshot-read-failed",
            "Export profile {profile} snapshot could not be read: {reason}"),
    STATE_TRANSITION_CONFLICT(DiagnosticSeverity.ERROR, "export.state-transition-conflict",
            "Export run {runId} cannot transition from {actualStatus} to {nextStatus}; expected {expectedStatus}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    ExportDiagnosticCodes(DiagnosticSeverity defaultSeverity,
                          String messageKey,
                          String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "EXPORT." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.EXPORT;
    }

    @Override
    public DiagnosticSeverity defaultSeverity() {
        return defaultSeverity;
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
