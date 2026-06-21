package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void notification_accumulates_diagnostics_and_converts_to_result() {
        var warning = diagnostic(DiagnosticSeverity.WARN);
        var error = diagnostic(DiagnosticSeverity.ERROR);

        var notification = new Notification()
                .add(warning)
                .addAll(List.of(error));

        assertThat(notification.diagnostics()).containsExactly(warning, error);
        assertThat(notification.hasErrors()).isTrue();
        assertThat(notification.hasFatal()).isFalse();
        assertThat(notification.toResult("value").diagnostics()).containsExactly(warning, error);
    }

    @Test
    void diagnostics_snapshot_is_immutable() {
        var notification = new Notification().add(diagnostic(DiagnosticSeverity.WARN));

        assertThatThrownBy(() -> notification.diagnostics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void throw_if_rejected_throws_only_at_orchestration_boundary() {
        var fatal = diagnostic(DiagnosticSeverity.FATAL);
        var notification = new Notification().add(fatal);

        assertThatThrownBy(() -> notification.throwIfRejected(FailurePolicy.collectAndContinue()))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic")
                .isEqualTo(fatal);
    }

    private Diagnostic diagnostic(DiagnosticSeverity severity) {
        return Diagnostic.builder(PipelineDiagnosticCodes.STAGE_FAILED, CLOCK)
                .severity(severity)
                .with("stage", "extract")
                .with("reason", "failed")
                .build();
    }
}
