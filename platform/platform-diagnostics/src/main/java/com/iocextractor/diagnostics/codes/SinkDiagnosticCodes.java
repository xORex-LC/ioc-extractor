package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for artifact sinks and row mapping.
 */
public enum SinkDiagnosticCodes implements DiagnosticCode {
    WRITE_FAILED(DiagnosticSeverity.FATAL, DiagnosticImpact.RUN, "sink.write-failed",
            "Sink {sink} failed to write artifact {artifact}: {reason}"),
    ROW_MAPPING_FAILED(DiagnosticSeverity.ERROR, DiagnosticImpact.ELEMENT, "sink.row-mapping-failed",
            "Sink {sink} failed to map row for indicator {indicator}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final DiagnosticImpact impact;
    private final String messageKey;
    private final String defaultMessageTemplate;

    SinkDiagnosticCodes(DiagnosticSeverity defaultSeverity, DiagnosticImpact impact,
                        String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.impact = impact;
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
