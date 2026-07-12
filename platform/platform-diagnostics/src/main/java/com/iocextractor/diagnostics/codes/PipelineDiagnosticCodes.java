package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for pipeline orchestration and stage execution.
 */
public enum PipelineDiagnosticCodes implements DiagnosticCode {
    STAGE_FAILED(DiagnosticSeverity.ERROR, DiagnosticImpact.RUN, "pipeline.stage-failed",
            "Pipeline stage {stage} failed: {reason}"),
    ITEM_SKIPPED(DiagnosticSeverity.WARN, DiagnosticImpact.ELEMENT, "pipeline.item-skipped",
            "Pipeline item {item} was skipped at stage {stage}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final DiagnosticImpact impact;
    private final String messageKey;
    private final String defaultMessageTemplate;

    PipelineDiagnosticCodes(DiagnosticSeverity defaultSeverity, DiagnosticImpact impact,
                            String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.impact = impact;
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
