package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for indicator classification.
 */
public enum ClassificationDiagnosticCodes implements DiagnosticCode {
    UNSUPPORTED_INDICATOR_TYPE(DiagnosticSeverity.ERROR, DiagnosticImpact.ELEMENT, "classify.unsupported-indicator-type",
            "Indicator type {type} is not supported by classifier {classifier}");

    private final DiagnosticSeverity defaultSeverity;
    private final DiagnosticImpact impact;
    private final String messageKey;
    private final String defaultMessageTemplate;

    ClassificationDiagnosticCodes(DiagnosticSeverity defaultSeverity, DiagnosticImpact impact, String messageKey,
                                  String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.impact = impact;
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
