package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionConvergenceResult;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockUnsafeException;
import com.iocextractor.application.artifact.lifecycle.LifecyclePolicyMismatchException;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconcileCycleId;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconciliationResult;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.LifecycleDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleRuntimeObserverTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    private final Logger logger = (Logger) LoggerFactory.getLogger(LifecycleRuntimeObserver.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

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

    @Test
    void idle_successes_are_silent_while_material_outcomes_remain_info() {
        ListAppender<ILoggingEvent> appender = appender();
        var observer = new LifecycleRuntimeObserver(
                NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK));
        EffectiveTime asOf = EffectiveTime.at(CLOCK.instant());

        observer.reconciliationCompleted(new LifecycleReconciliationResult(
                new LifecycleReconcileCycleId(1), asOf, 0, 0, List.of()));
        observer.projectionCompleted(new ArtifactProjectionConvergenceResult(0, 0, List.of()));

        assertThat(appender.list).isEmpty();

        observer.reconciliationCompleted(new LifecycleReconciliationResult(
                new LifecycleReconcileCycleId(2), asOf, 3, 1, List.of("masks")));
        observer.projectionCompleted(new ArtifactProjectionConvergenceResult(
                1, 0, List.of("masks")));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.INFO, Level.INFO);
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
