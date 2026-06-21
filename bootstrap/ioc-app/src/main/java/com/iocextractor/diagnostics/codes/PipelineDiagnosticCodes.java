package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for pipeline orchestration and stage execution.
 */
public enum PipelineDiagnosticCodes implements DiagnosticCode {
    STAGE_FAILED(DiagnosticSeverity.ERROR, "pipeline.stage-failed",
            "Pipeline stage {stage} failed: {reason}"),
    ITEM_SKIPPED(DiagnosticSeverity.WARN, "pipeline.item-skipped",
            "Pipeline item {item} was skipped at stage {stage}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    PipelineDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "PIPELINE." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.PIPELINE;
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
