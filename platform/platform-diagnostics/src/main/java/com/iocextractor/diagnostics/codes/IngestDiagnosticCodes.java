package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for whole-file ingestion.
 */
public enum IngestDiagnosticCodes implements DiagnosticCode {
    CLAIM_FAILED(DiagnosticSeverity.FATAL, "ingest.claim-failed",
            "Source {source} could not be claimed for ingestion: {reason}"),
    LEDGER_WRITE_FAILED(DiagnosticSeverity.FATAL, "ingest.ledger-write-failed",
            "Ingestion ledger update failed for source {source}: {reason}"),
    RECOVERY_FAILED(DiagnosticSeverity.ERROR, "ingest.recovery-failed",
            "Ingestion recovery failed for source {source}: {reason}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    IngestDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "INGEST." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.INGEST;
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
