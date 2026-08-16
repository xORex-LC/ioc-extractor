package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleDeadline;
import com.iocextractor.application.artifact.lifecycle.LifecycleHistoryRetentionResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconcileCycleId;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconciliationResult;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleDeadlineSchedulerTest {

    private static final Instant START = Instant.parse("2026-08-16T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(START, ZoneOffset.UTC);

    @Test
    void scheduler_is_inert_until_active_admission_and_uses_nearest_deadline() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicInteger executorCreations = new AtomicInteger();
        RecordingExecutor executor = new RecordingExecutor();
        ExpiredArtifactStore deadlines = new ExpiredArtifactStore() {
            @Override
            public Optional<LifecycleDeadline> nearestDeadline() {
                return Optional.of(new LifecycleDeadline(START.plusSeconds(5)));
            }

            @Override
            public com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult expireDue(
                    String artifactName, EffectiveTime cycleAsOf, int batchSize) {
                throw new UnsupportedOperationException();
            }
        };
        LifecycleRuntimeObserver observer = new LifecycleRuntimeObserver(
                NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK));
        var scheduler = new LifecycleDeadlineScheduler(
                admission,
                deadlines,
                () -> new LifecycleReconciliationResult(
                        new LifecycleReconcileCycleId(1), EffectiveTime.at(START), 0, 0, List.of()),
                () -> new LifecycleHistoryRetentionResult(0, false, Map.of()),
                Duration.ofSeconds(5),
                CLOCK,
                observer,
                () -> {
                    executorCreations.incrementAndGet();
                    return executor;
                });

        scheduler.start();
        assertThat(executorCreations).hasValue(0);

        admission.admitted(activeAdmission());
        try {
            assertThat(executorCreations).hasValue(1);
            assertThat(executor.deadlineDelayMillis).isBetween(0L, 5_000L);
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void disabled_compatible_admission_keeps_deadline_worker_dormant() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicInteger executorCreations = new AtomicInteger();
        LifecycleRuntimeObserver observer = new LifecycleRuntimeObserver(
                NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK));
        var scheduler = new LifecycleDeadlineScheduler(
                admission,
                new EmptyDeadlineStore(),
                () -> new LifecycleReconciliationResult(
                        new LifecycleReconcileCycleId(1), EffectiveTime.at(START), 0, 0, List.of()),
                () -> new LifecycleHistoryRetentionResult(0, false, Map.of()),
                Duration.ofSeconds(5), CLOCK, observer,
                () -> {
                    executorCreations.incrementAndGet();
                    return new RecordingExecutor();
                });

        scheduler.start();
        admission.admitted(new LifecycleAdmissionResult(
                LifecycleActivationState.DISABLED_COMPATIBLE,
                EffectiveTime.at(START), 0, 0));
        scheduler.stop();

        assertThat(executorCreations).hasValue(0);
    }

    private LifecycleAdmissionResult activeAdmission() {
        return new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, EffectiveTime.at(START), 0, 0);
    }

    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {

        private volatile long deadlineDelayMillis = -1;

        private RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            deadlineDelayMillis = unit.toMillis(delay);
            return super.schedule(command, Duration.ofDays(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static final class EmptyDeadlineStore implements ExpiredArtifactStore {

        @Override
        public Optional<LifecycleDeadline> nearestDeadline() {
            return Optional.empty();
        }

        @Override
        public com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult expireDue(
                String artifactName, EffectiveTime cycleAsOf, int batchSize) {
            throw new UnsupportedOperationException();
        }
    }
}
