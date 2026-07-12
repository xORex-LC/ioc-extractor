package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for configuration validation and policy selection.
 */
public enum ConfigDiagnosticCodes implements DiagnosticCode {
    INVALID_PROPERTY(DiagnosticSeverity.FATAL, DiagnosticImpact.RUN, "config.invalid-property",
            "Invalid configuration property {property}: {reason}"),
    UNKNOWN_POLICY(DiagnosticSeverity.FATAL, DiagnosticImpact.RUN, "config.unknown-policy",
            "Unknown policy {policy}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final DiagnosticImpact impact;
    private final String messageKey;
    private final String defaultMessageTemplate;

    ConfigDiagnosticCodes(DiagnosticSeverity defaultSeverity, DiagnosticImpact impact,
                          String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.impact = impact;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "CONFIG." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.CONFIG;
    }

    @Override
    public DiagnosticSeverity defaultSeverity() {
        return defaultSeverity;
    }

    @Override
    public DiagnosticImpact impact() {
        return impact;
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
