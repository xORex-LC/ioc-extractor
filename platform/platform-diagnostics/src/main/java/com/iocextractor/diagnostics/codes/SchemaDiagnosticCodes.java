package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for storage schema reconciliation.
 */
public enum SchemaDiagnosticCodes implements DiagnosticCode {
    SCHEMA_ADDED(DiagnosticSeverity.INFO, "storage.schema-added",
            "Artifact {artifact} schema added column {column}"),
    SCHEMA_DESTRUCTIVE_CHANGE(DiagnosticSeverity.FATAL, "storage.schema-destructive-change",
            "Artifact {artifact} schema has destructive change {change}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    SchemaDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "STORAGE." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.STORAGE;
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
