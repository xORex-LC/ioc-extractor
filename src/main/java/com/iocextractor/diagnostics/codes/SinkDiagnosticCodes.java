package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for artifact sinks and row mapping.
 */
public enum SinkDiagnosticCodes implements DiagnosticCode {
    WRITE_FAILED(DiagnosticSeverity.FATAL, "sink.write-failed",
            "Sink {sink} failed to write artifact {artifact}: {reason}"),
    ROW_MAPPING_FAILED(DiagnosticSeverity.ERROR, "sink.row-mapping-failed",
            "Sink {sink} failed to map row for indicator {indicator}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    SinkDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "SINK." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.SINK;
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
