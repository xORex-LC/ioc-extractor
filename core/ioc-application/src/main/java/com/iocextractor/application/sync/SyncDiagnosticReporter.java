package com.iocextractor.application.sync;

import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SyncDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;

import java.time.Clock;
import java.util.Objects;

/** Maps transport-neutral terminal failures to the canonical SYNC diagnostic catalog. */
public final class SyncDiagnosticReporter {

    private final DiagnosticSink sink;
    private final DiagnosticFactory diagnostics;

    /** Creates a reporter whose caller remains responsible for durable state transitions. */
    public SyncDiagnosticReporter(DiagnosticSink sink, Clock clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.diagnostics = new DiagnosticFactory(Objects.requireNonNull(clock, "clock"));
    }

    /**
     * Emits one final transport failure after retry exhaustion and, where present,
     * after the caller has durably recorded the failed operation.
     */
    public void report(RemoteTransportException failure,
                       String endpoint,
                       String path,
                       String operation) {
        Objects.requireNonNull(failure, "failure");
        sink.emit(diagnostics.create(code(failure.kind()))
                .with("endpoint", endpoint)
                .with("path", path)
                .with("operation", operation)
                .with("reason", reason(failure))
                .cause(failure)
                .build());
    }

    private SyncDiagnosticCodes code(RemoteErrorKind kind) {
        return switch (kind) {
            case UNREACHABLE -> SyncDiagnosticCodes.ENDPOINT_UNREACHABLE;
            case AUTH_FAILED -> SyncDiagnosticCodes.AUTH_FAILED;
            case PERMISSION_DENIED -> SyncDiagnosticCodes.PERMISSION_DENIED;
            case NOT_FOUND -> SyncDiagnosticCodes.REMOTE_NOT_FOUND;
            case SECURITY_POLICY_UNMET -> SyncDiagnosticCodes.SECURITY_POLICY_UNMET;
            case RESOURCE_EXHAUSTED -> SyncDiagnosticCodes.REMOTE_RESOURCE_EXHAUSTED;
            case TRANSIENT -> SyncDiagnosticCodes.TRANSPORT_TRANSIENT;
        };
    }

    private String reason(RemoteTransportException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.kind().name().toLowerCase(java.util.Locale.ROOT)
                : failure.getMessage();
    }
}
