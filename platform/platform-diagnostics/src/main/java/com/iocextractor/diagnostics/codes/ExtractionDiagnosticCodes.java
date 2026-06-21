package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for IOC extraction.
 */
public enum ExtractionDiagnosticCodes implements DiagnosticCode {
    PATTERN_INVALID(DiagnosticSeverity.FATAL, "extraction.pattern-invalid",
            "Extraction pattern {pattern} is invalid: {reason}"),
    INDICATOR_SKIPPED(DiagnosticSeverity.DEBUG, "extraction.indicator-skipped",
            "Indicator {indicator} was skipped: {reason}"),
    AMBIGUOUS_VALUE(DiagnosticSeverity.WARN, "extraction.ambiguous-value",
            "Value {value} is ambiguous: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    ExtractionDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "EXTRACTION." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.EXTRACTION;
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
