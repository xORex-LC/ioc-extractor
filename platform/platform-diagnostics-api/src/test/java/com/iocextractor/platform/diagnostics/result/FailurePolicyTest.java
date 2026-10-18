package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.TestCodes;
import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FailurePolicyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void policies_select_in_encounter_order_without_changing_occurrences() {
        var warning = diagnostic(DiagnosticSeverity.WARN);
        var firstError = diagnostic(DiagnosticSeverity.ERROR);
        var fatal = diagnostic(DiagnosticSeverity.FATAL);
        var notification = new Notification().addAll(java.util.List.of(warning, firstError, fatal, firstError));

        assertThat(FailurePolicy.failFast().evaluate(notification).diagnostic()).containsSame(firstError);
        assertThat(FailurePolicy.collectAndContinue().evaluate(notification).diagnostic()).containsSame(fatal);
        assertThat(notification.diagnostics()).containsExactly(warning, firstError, fatal, firstError);
    }

    @Test
    void empty_notifications_continue_and_null_policy_inputs_are_rejected() {
        for (var policy : java.util.List.of(FailurePolicy.failFast(), FailurePolicy.collectAndContinue())) {
            var decision = policy.evaluate(new Notification());
            assertThat(decision.shouldStop()).isFalse();
            assertThat(decision.diagnostic()).isEmpty();
            org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> policy.evaluate(null));
        }
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> FailureDecision.stop(null));
    }

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
        return Diagnostic.builder(TestCodes.STAGE_FAILED, CLOCK)
                .severity(severity)
                .with("stage", "extract")
                .with("reason", "failed")
                .build();
    }
}
