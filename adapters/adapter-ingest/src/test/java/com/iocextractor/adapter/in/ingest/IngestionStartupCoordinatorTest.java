package com.iocextractor.adapter.in.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.context.Lifecycle;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionStartupCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void opensIntakeOnlyAfterRunAndSourceRecoveryComplete() throws Exception {
        var events = new ArrayList<String>();
        var sourceRecoveryEntered = new CountDownLatch(1);
        var releaseSourceRecovery = new CountDownLatch(1);
        var intake = new RecordingLifecycle(events);
        var state = new IngestionLifecycleState();
        var observer = new RecordingStartupObserver(events);
        var coordinator = new IngestionStartupCoordinator(
                () -> {
                    events.add("run-recovery");
                    return 2;
                },
                () -> {
                    events.add("source-recovery");
                    sourceRecoveryEntered.countDown();
                    await(releaseSourceRecovery);
                    return List.of();
                },
                intake,
                state,
                observer,
                CLOCK);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var startup = executor.submit(() -> coordinator.run(null));
            assertThat(sourceRecoveryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(intake.isRunning()).isFalse();
            assertThat(events).containsExactly("recovery-started", "run-recovery", "source-recovery");
            assertThat(state.snapshot().phase()).isEqualTo(IngestionLifecycleState.Phase.RECOVERING);

            releaseSourceRecovery.countDown();
            startup.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSourceRecovery.countDown();
        }

        assertThat(intake.isRunning()).isTrue();
        assertThat(events).containsExactly(
                "recovery-started", "run-recovery", "source-recovery",
                "intake-start", "recovery-completed");
        assertThat(state.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.phase()).isEqualTo(IngestionLifecycleState.Phase.RUNNING);
            assertThat(snapshot.recoveredRuns()).isEqualTo(2);
            assertThat(snapshot.recoveredSources()).isZero();
            assertThat(snapshot.recoveryStartedAt()).isEqualTo(NOW);
            assertThat(snapshot.recoveryCompletedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void leavesIntakeStoppedWhenRunRecoveryFails() {
        var sourceRecoveryCalls = new ArrayList<String>();
        var events = new ArrayList<String>();
        var intake = new RecordingLifecycle(events);
        var state = new IngestionLifecycleState();
        var coordinator = new IngestionStartupCoordinator(
                () -> {
                    throw new IllegalStateException("run recovery failed");
                },
                () -> {
                    sourceRecoveryCalls.add("called");
                    return List.of();
                },
                intake,
                state,
                new RecordingStartupObserver(events),
                CLOCK);

        assertThatThrownBy(() -> coordinator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run recovery failed");

        assertThat(sourceRecoveryCalls).isEmpty();
        assertThat(intake.isRunning()).isFalse();
        assertThat(events).containsExactly("recovery-started", "intake-stop", "recovery-failed");
        assertThat(state.snapshot().phase()).isEqualTo(IngestionLifecycleState.Phase.FAILED);
        assertThat(state.snapshot().failure()).isEqualTo("IllegalStateException");
    }

    @Test
    void leavesIntakeStoppedWhenSourceRecoveryFails() {
        var events = new ArrayList<String>();
        var intake = new RecordingLifecycle(events);
        var state = new IngestionLifecycleState();
        var coordinator = new IngestionStartupCoordinator(
                () -> 0,
                () -> {
                    throw new IllegalStateException("source recovery failed");
                },
                intake,
                state,
                new RecordingStartupObserver(events),
                CLOCK);

        assertThatThrownBy(() -> coordinator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source recovery failed");

        assertThat(intake.isRunning()).isFalse();
        assertThat(events).containsExactly("recovery-started", "intake-stop", "recovery-failed");
        assertThat(state.snapshot().phase()).isEqualTo(IngestionLifecycleState.Phase.FAILED);
    }

    @Test
    void rejectsAnAlreadyRunningIntakeBeforeRecovery() {
        var events = new ArrayList<String>();
        var intake = new RecordingLifecycle(events);
        intake.start();
        var state = new IngestionLifecycleState();
        var coordinator = new IngestionStartupCoordinator(
                () -> {
                    events.add("run-recovery");
                    return 0;
                },
                () -> List.of(),
                intake,
                state,
                new RecordingStartupObserver(events),
                CLOCK);

        assertThatThrownBy(() -> coordinator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must remain stopped");

        assertThat(events).containsExactly(
                "intake-start", "recovery-started", "intake-stop", "recovery-failed");
        assertThat(state.snapshot().phase()).isEqualTo(IngestionLifecycleState.Phase.FAILED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test coordination");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test coordination interrupted", failure);
        }
    }

    private static final class RecordingLifecycle implements Lifecycle {
        private final List<String> events;
        private boolean running;

        private RecordingLifecycle(List<String> events) {
            this.events = events;
        }

        @Override
        public void start() {
            events.add("intake-start");
            running = true;
        }

        @Override
        public void stop() {
            events.add("intake-stop");
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private static final class RecordingStartupObserver implements IngestionStartupObserver {
        private final List<String> events;

        private RecordingStartupObserver(List<String> events) {
            this.events = events;
        }

        @Override
        public void recoveryStarted(Instant startedAt) {
            events.add("recovery-started");
        }

        @Override
        public void recoveryCompleted(Instant startedAt, Instant completedAt,
                                      int recoveredRuns, int recoveredSources) {
            events.add("recovery-completed");
        }

        @Override
        public void recoveryFailed(Instant startedAt, Instant failedAt, RuntimeException failure) {
            events.add("recovery-failed");
        }
    }
}
