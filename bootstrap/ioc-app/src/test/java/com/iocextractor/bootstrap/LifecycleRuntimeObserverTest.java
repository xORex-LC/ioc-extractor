package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.LifecycleClockUnsafeException;
import com.iocextractor.application.artifact.lifecycle.LifecyclePolicyMismatchException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.LifecycleDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleRuntimeObserverTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void admission_reports_policy_mismatch_and_clock_failure_with_stable_codes() {
        var diagnostics = new CollectingDiagnosticSink();
        var observer = new LifecycleRuntimeObserver(diagnostics, new DiagnosticFactory(CLOCK));

        observer.admissionFailed(new LifecyclePolicyMismatchException("disabled after activation"));
        observer.admissionFailed(new LifecycleClockUnsafeException("clock moved backwards"));

        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly(
                        LifecycleDiagnosticCodes.POLICY_MISMATCH,
                        LifecycleDiagnosticCodes.CLOCK_UNSAFE);
    }
}
