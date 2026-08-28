package com.iocextractor.application.sync;

import com.iocextractor.diagnostics.codes.SyncDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class SyncDiagnosticReporterTest {

    @ParameterizedTest
    @EnumSource(RemoteErrorKind.class)
    void mapsEveryTransportKindToOneCanonicalDiagnostic(RemoteErrorKind kind) {
        var sink = new CollectingDiagnosticSink();
        var reporter = new SyncDiagnosticReporter(sink, Clock.systemUTC());

        reporter.report(new RemoteTransportException(kind, "transport failed"),
                "endpoint-a", "/remote/path", "fetch");

        assertThat(sink.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(expected(kind));
            assertThat(diagnostic.context())
                    .containsEntry("endpoint", "endpoint-a")
                    .containsEntry("path", "/remote/path")
                    .containsEntry("operation", "fetch")
                    .containsEntry("reason", "transport failed");
        });
    }

    private SyncDiagnosticCodes expected(RemoteErrorKind kind) {
        return switch (kind) {
            case UNREACHABLE -> SyncDiagnosticCodes.ENDPOINT_UNREACHABLE;
            case AUTH_FAILED -> SyncDiagnosticCodes.AUTH_FAILED;
            case PERMISSION_DENIED -> SyncDiagnosticCodes.PERMISSION_DENIED;
            case NOT_FOUND -> SyncDiagnosticCodes.REMOTE_NOT_FOUND;
            case SECURITY_POLICY_UNMET -> SyncDiagnosticCodes.SECURITY_POLICY_UNMET;
            case TRANSIENT -> SyncDiagnosticCodes.TRANSPORT_TRANSIENT;
        };
    }
}
