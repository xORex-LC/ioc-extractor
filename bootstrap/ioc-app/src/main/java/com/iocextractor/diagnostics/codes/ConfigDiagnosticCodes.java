package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for configuration validation and policy selection.
 */
public enum ConfigDiagnosticCodes implements DiagnosticCode {
    INVALID_PROPERTY(DiagnosticSeverity.FATAL, "config.invalid-property",
            "Invalid configuration property {property}: {reason}"),
    UNKNOWN_POLICY(DiagnosticSeverity.FATAL, "config.unknown-policy",
            "Unknown policy {policy}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    ConfigDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
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
    public String messageKey() {
        return messageKey;
    }

    @Override
    public String defaultMessageTemplate() {
        return defaultMessageTemplate;
    }
}
