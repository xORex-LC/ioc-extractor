package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.TestCodes;
import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    @Test
    void failed_bulk_addition_keeps_prior_occurrences_and_does_not_change_earlier_snapshots() {
        var warning = diagnostic(DiagnosticSeverity.WARN);
        var notification = new Notification().add(warning);
        var snapshot = notification.diagnostics();
        var result = notification.toResult("value");

        assertThatThrownBy(() -> notification.addAll(java.util.Arrays.asList(warning, null, warning)))
                .isInstanceOf(NullPointerException.class);

        assertThat(notification.diagnostics()).containsExactly(warning, warning);
        assertThat(snapshot).containsExactly(warning);
        assertThat(result.diagnostics()).containsExactly(warning);
        assertThatThrownBy(() -> notification.add(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> notification.addAll(null)).isInstanceOf(NullPointerException.class);
        assertThat(notification.diagnostics()).containsExactly(warning, warning);
    }

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
    void policy_returns_the_diagnostic_for_caller_owned_enforcement() {
        var fatal = diagnostic(DiagnosticSeverity.FATAL);
        var notification = new Notification().add(fatal);

        var decision = FailurePolicy.collectAndContinue().evaluate(notification);
        assertThat(decision.shouldStop()).isTrue();
        assertThat(decision.diagnostic()).containsSame(fatal);
    }

    private Diagnostic diagnostic(DiagnosticSeverity severity) {
        return Diagnostic.builder(TestCodes.STAGE_FAILED, CLOCK)
                .severity(severity)
                .with("stage", "extract")
                .with("reason", "failed")
                .build();
    }
}
