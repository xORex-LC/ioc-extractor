package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FailurePolicyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void fail_fast_stops_on_error() {
        var error = diagnostic(DiagnosticSeverity.ERROR);
        var notification = new Notification().add(error);

        var decision = FailurePolicy.failFast().evaluate(notification);

        assertThat(decision.shouldStop()).isTrue();
        assertThat(decision.diagnostic()).contains(error);
    }

    @Test
    void collect_and_continue_stops_only_on_fatal() {
        var error = diagnostic(DiagnosticSeverity.ERROR);
        var notification = new Notification().add(error);

        assertThat(FailurePolicy.collectAndContinue().evaluate(notification).shouldStop()).isFalse();

        notification.add(diagnostic(DiagnosticSeverity.FATAL));
        assertThat(FailurePolicy.collectAndContinue().evaluate(notification).shouldStop()).isTrue();
    }

    @Test
    void policies_do_not_mutate_notifications() {
        var warning = diagnostic(DiagnosticSeverity.WARN);
        var notification = new Notification().add(warning);

        FailurePolicy.failFast().evaluate(notification);
        FailurePolicy.collectAndContinue().evaluate(notification);

        assertThat(notification.diagnostics()).containsExactly(warning);
    }

    private Diagnostic diagnostic(DiagnosticSeverity severity) {
        return Diagnostic.builder(PipelineDiagnosticCodes.STAGE_FAILED, CLOCK)
                .severity(severity)
                .with("stage", "extract")
                .with("reason", "failed")
                .build();
    }
}
