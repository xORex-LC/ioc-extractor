package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/** Diagnostic codes produced by remote fetch/publish synchronization. */
public enum SyncDiagnosticCodes implements DiagnosticCode {
    ENDPOINT_UNREACHABLE(DiagnosticSeverity.ERROR, "sync.endpoint-unreachable",
            "Sync endpoint {endpoint} is unreachable: {reason}"),
    AUTH_FAILED(DiagnosticSeverity.FATAL, "sync.auth-failed",
            "Sync endpoint {endpoint} rejected credentials: {reason}"),
    PERMISSION_DENIED(DiagnosticSeverity.ERROR, "sync.permission-denied",
            "Sync endpoint {endpoint} denied access to {path}: {reason}"),
    REMOTE_NOT_FOUND(DiagnosticSeverity.WARN, "sync.remote-not-found",
            "Remote path {path} was not found on sync endpoint {endpoint}"),
    TRANSPORT_TRANSIENT(DiagnosticSeverity.WARN, "sync.transport-transient",
            "Transient transport failure on sync endpoint {endpoint}: {reason}"),
    PUBLISH_VERIFY_FAILED(DiagnosticSeverity.ERROR, "sync.publish-verify-failed",
            "Published slice {sliceId} for target {targetId} failed verification: {reason}"),
    ENDPOINT_UNKNOWN(DiagnosticSeverity.FATAL, "sync.endpoint-unknown",
            "Sync configuration references unknown endpoint {endpoint} from {owner}"),
    CREDENTIAL_MISSING(DiagnosticSeverity.FATAL, "sync.credential-missing",
            "Sync endpoint {endpoint} is missing required credential {credential}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    SyncDiagnosticCodes(DiagnosticSeverity defaultSeverity,
                        String messageKey,
                        String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "SYNC." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.SYNC;
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
