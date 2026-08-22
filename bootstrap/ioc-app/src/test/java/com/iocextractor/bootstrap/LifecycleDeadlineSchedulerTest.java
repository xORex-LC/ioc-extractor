package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleDeadline;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void ten_thousand_empty_backstop_refreshes_do_not_start_reconciliation() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger reconciliations = new AtomicInteger();
        RecordingExecutor executor = new RecordingExecutor();
        LifecycleRuntimeObserver observer = new LifecycleRuntimeObserver(
                NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK));
        ExpiredArtifactStore deadlines = new ExpiredArtifactStore() {
            @Override
            public Optional<LifecycleDeadline> nearestDeadline() {
                lookups.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult expireDue(
                    String artifactName, EffectiveTime cycleAsOf, int batchSize) {
                throw new UnsupportedOperationException();
            }
        };
        var scheduler = new LifecycleDeadlineScheduler(
                admission,
                deadlines,
                () -> {
                    reconciliations.incrementAndGet();
                    return new LifecycleReconciliationResult(
                            new LifecycleReconcileCycleId(1), EffectiveTime.at(START), 0, 0, List.of());
                },
                Duration.ofSeconds(5), CLOCK, observer, () -> executor);

        scheduler.start();
        admission.admitted(activeAdmission());
        try {
            for (int tick = 0; tick < 10_000; tick++) {
                executor.runBackstop();
            }
        } finally {
            scheduler.stop();
        }

        assertThat(lookups).hasValue(10_001);
        assertThat(reconciliations).hasValue(0);
    }

    @Test
    void reconciliation_does_not_overlap_when_due_hints_race() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reconciliations = new AtomicInteger();
        var scheduler = new LifecycleDeadlineScheduler(
                new CanonicalDataAdmissionState(),
                new EmptyDeadlineStore(),
                () -> {
                    reconciliations.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return new LifecycleReconciliationResult(
                            new LifecycleReconcileCycleId(1), EffectiveTime.at(START), 0, 0, List.of());
                },
                Duration.ofSeconds(5), CLOCK,
                new LifecycleRuntimeObserver(
                        NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK)));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(scheduler::runOnce);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var racing = executor.submit(scheduler::runOnce);
            racing.get(1, TimeUnit.SECONDS);
            release.countDown();
            first.get(1, TimeUnit.SECONDS);
        }

        assertThat(reconciliations).hasValue(1);
    }

    @Test
    void failed_due_attempt_waits_for_the_periodic_backstop_before_retrying() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicInteger lookups = new AtomicInteger();
        RecordingExecutor executor = new RecordingExecutor();
        ExpiredArtifactStore due = new ExpiredArtifactStore() {
            @Override
            public Optional<LifecycleDeadline> nearestDeadline() {
                lookups.incrementAndGet();
                return Optional.of(new LifecycleDeadline(START));
            }

            @Override
            public com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult expireDue(
                    String artifactName, EffectiveTime cycleAsOf, int batchSize) {
                throw new UnsupportedOperationException();
            }
        };
        var scheduler = new LifecycleDeadlineScheduler(
                admission, due,
                () -> {
                    throw new IllegalStateException("sqlite unavailable");
                },
                Duration.ofSeconds(5), CLOCK,
                new LifecycleRuntimeObserver(
                        NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK)),
                () -> executor);

        scheduler.start();
        admission.admitted(activeAdmission());
        try {
            executor.runDeadline();
            assertThat(lookups).hasValue(1);

            executor.runBackstop();
            assertThat(lookups).hasValue(2);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void periodic_backstop_recovers_a_deadline_when_the_event_hint_was_lost() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicBoolean deadlinePresent = new AtomicBoolean();
        AtomicInteger reconciliations = new AtomicInteger();
        RecordingExecutor executor = new RecordingExecutor();
        ExpiredArtifactStore deadlines = new ExpiredArtifactStore() {
            @Override
            public Optional<LifecycleDeadline> nearestDeadline() {
                return deadlinePresent.get()
                        ? Optional.of(new LifecycleDeadline(START))
                        : Optional.empty();
            }

            @Override
            public com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult expireDue(
                    String artifactName, EffectiveTime cycleAsOf, int batchSize) {
                throw new UnsupportedOperationException();
            }
        };
        var scheduler = new LifecycleDeadlineScheduler(
                admission, deadlines,
                () -> {
                    reconciliations.incrementAndGet();
                    deadlinePresent.set(false);
                    return new LifecycleReconciliationResult(
                            new LifecycleReconcileCycleId(1), EffectiveTime.at(START), 1, 1,
                            List.of("masks"));
                },
                Duration.ofSeconds(5), CLOCK,
                new LifecycleRuntimeObserver(
                        NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(CLOCK)),
                () -> executor);

        scheduler.start();
        admission.admitted(activeAdmission());
        try {
            deadlinePresent.set(true);
            executor.runBackstop();
            executor.runDeadline();
        } finally {
            scheduler.stop();
        }

        assertThat(reconciliations).hasValue(1);
    }

    private LifecycleAdmissionResult activeAdmission() {
        return new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, EffectiveTime.at(START), 0, 0);
    }

    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {

        private volatile long deadlineDelayMillis = -1;
        private volatile Runnable backstop;
        private volatile Runnable deadline;

        private RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            deadlineDelayMillis = unit.toMillis(delay);
            deadline = command;
            return super.schedule(command, Duration.ofDays(1).toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            backstop = command;
            return super.scheduleWithFixedDelay(
                    command, Duration.ofDays(1).toMillis(), Duration.ofDays(1).toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        private void runBackstop() {
            backstop.run();
        }

        private void runDeadline() {
            deadline.run();
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
