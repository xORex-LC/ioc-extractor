package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleHistoryRetentionResult;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleHistoryRetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void active_scheduler_runs_independently_and_drains_bounded_backlog() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        RecordingExecutor executor = new RecordingExecutor();
        AtomicInteger passes = new AtomicInteger();
        var scheduler = new LifecycleHistoryRetentionScheduler(
                admission,
                () -> {
                    int pass = passes.incrementAndGet();
                    return new LifecycleHistoryRetentionResult(
                            1, pass == 1, Map.of("masks", 1));
                },
                Duration.ofHours(1),
                new LifecycleRuntimeObserver(
                        NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK)),
                () -> executor);

        scheduler.start();
        assertThat(executor.periodic).isNull();
        admission.admitted(new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, EffectiveTime.at(NOW), 0, 0));
        try {
            executor.runPeriodic();
            assertThat(passes).hasValue(1);
            assertThat(executor.immediate).hasSize(1);

            executor.runImmediate();
            assertThat(passes).hasValue(2);
            assertThat(executor.immediate).isEmpty();
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void queued_follow_up_does_not_run_after_scheduler_stops() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        RecordingExecutor executor = new RecordingExecutor();
        AtomicInteger passes = new AtomicInteger();
        var scheduler = new LifecycleHistoryRetentionScheduler(
                admission,
                () -> {
                    passes.incrementAndGet();
                    return new LifecycleHistoryRetentionResult(1, true, Map.of("masks", 1));
                },
                Duration.ofHours(1),
                new LifecycleRuntimeObserver(
                        NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK)),
                () -> executor);

        scheduler.start();
        admission.admitted(new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, EffectiveTime.at(NOW), 0, 0));
        executor.runPeriodic();
        assertThat(executor.immediate).hasSize(1);

        scheduler.stop();
        executor.runImmediate();

        assertThat(passes).hasValue(1);
    }

    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {

        private final Queue<Runnable> immediate = new ArrayDeque<>();
        private Runnable periodic;

        private RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            periodic = command;
            return super.scheduleWithFixedDelay(
                    command, Duration.ofDays(1).toMillis(), Duration.ofDays(1).toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        @Override
        public void execute(Runnable command) {
            immediate.add(command);
        }

        private void runPeriodic() {
            periodic.run();
        }

        private void runImmediate() {
            immediate.remove().run();
        }
    }
}
