package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/** Stable value-free diagnostics for managed dataframe delivery coordination. */
public enum ImportDiagnosticCodes implements DiagnosticCode {
    CLAIM_FAILED(DiagnosticSeverity.ERROR, "import.claim-failed",
            "Managed import claim failed; durable retry remains scheduled"),
    INPUT_INVALID(DiagnosticSeverity.ERROR, "import.input-invalid",
            "Managed import input failed its declared structural contract"),
    CONTRACT_NOT_RECOGNIZED(DiagnosticSeverity.ERROR, "import.contract-not-recognized",
            "Managed import input matched no allowlisted contract"),
    CONTRACT_AMBIGUOUS(DiagnosticSeverity.ERROR, "import.contract-ambiguous",
            "Managed import input matched more than one allowlisted contract"),
    LIMIT_EXCEEDED(DiagnosticSeverity.ERROR, "import.limit-exceeded",
            "Managed import input exceeded a configured hard resource limit"),
    CAPACITY_PAUSED(DiagnosticSeverity.WARN, "import.capacity-paused",
            "Managed import intake is safely paused at a configured capacity watermark"),
    PROCESSING_FAILED(DiagnosticSeverity.ERROR, "import.processing-failed",
            "Managed import processing failed; durable retry remains scheduled"),
    FINALIZATION_FAILED(DiagnosticSeverity.ERROR, "import.finalization-failed",
            "Managed import finalization failed; forward recovery remains scheduled"),
    CONSISTENCY_FAILED(DiagnosticSeverity.FATAL, "import.consistency-failed",
            "Managed import durable evidence is contradictory and intake remains closed"),
    RETENTION_FAILED(DiagnosticSeverity.ERROR, "import.retention-failed",
            "Managed import terminal retention failed and will be retried"),
    CHANGE_SIGNAL_FAILED(DiagnosticSeverity.WARN, "import.change-signal-failed",
            "Managed import change notification is unavailable; polling remains active");

    private final DiagnosticSeverity severity;
    private final String messageKey;
    private final String message;

    ImportDiagnosticCodes(DiagnosticSeverity severity, String messageKey, String message) {
        this.severity = severity;
        this.messageKey = messageKey;
        this.message = message;
    }

    @Override
    public String id() {
        return "IMPORT." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.IMPORT;
    }

    @Override
    public DiagnosticSeverity defaultSeverity() {
        return severity;
    }

    @Override
    public DiagnosticImpact impact() {
        return DiagnosticImpact.OPERATION;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }

    @Override
    public String defaultMessageTemplate() {
        return message;
    }
}
