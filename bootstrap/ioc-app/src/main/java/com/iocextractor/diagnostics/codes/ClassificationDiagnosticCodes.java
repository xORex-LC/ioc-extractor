package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for indicator classification.
 */
public enum ClassificationDiagnosticCodes implements DiagnosticCode {
    AMBIGUOUS_MATCH(DiagnosticSeverity.WARN, "classify.ambiguous-match",
            "Indicator {indicator} matched multiple classification rules: {candidates}"),
    UNSUPPORTED_INDICATOR_TYPE(DiagnosticSeverity.ERROR, "classify.unsupported-indicator-type",
            "Indicator type {type} is not supported by classifier {classifier}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    ClassificationDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey,
                                  String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "CLASSIFY." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.CLASSIFY;
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
