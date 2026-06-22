package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for source document reading.
 */
public enum SourceDiagnosticCodes implements DiagnosticCode {
    READ_FAILED(DiagnosticSeverity.FATAL, "source.read-failed",
            "Source {source} could not be read: {reason}"),
    UNSUPPORTED_FORMAT(DiagnosticSeverity.ERROR, "source.unsupported-format",
            "Source {source} has unsupported format {format}"),
    EMPTY_TEXT(DiagnosticSeverity.WARN, "source.empty-text",
            "Source {source} produced empty text"),
    MARKERS_UNMATCHED(DiagnosticSeverity.WARN, "source.markers-unmatched",
            "No section marker matched {unattributed} of {total} indicator(s); source left empty (extend ioc.source.section-markers)");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    SourceDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "SOURCE." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.SOURCE;
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
