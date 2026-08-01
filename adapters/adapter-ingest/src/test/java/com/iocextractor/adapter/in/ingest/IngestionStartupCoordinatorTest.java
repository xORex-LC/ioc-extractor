package com.iocextractor.adapter.in.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.context.Lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionStartupCoordinatorTest {

    @Test
    void opensIntakeOnlyAfterRunAndSourceRecoveryComplete() throws Exception {
        var events = new ArrayList<String>();
        var sourceRecoveryEntered = new CountDownLatch(1);
        var releaseSourceRecovery = new CountDownLatch(1);
        var intake = new RecordingLifecycle(events);
        var coordinator = new IngestionStartupCoordinator(
                () -> events.add("run-recovery"),
                () -> {
                    events.add("source-recovery");
                    sourceRecoveryEntered.countDown();
                    await(releaseSourceRecovery);
                    return List.of();
                },
                intake);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var startup = executor.submit(() -> coordinator.run(null));
            assertThat(sourceRecoveryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(intake.isRunning()).isFalse();
            assertThat(events).containsExactly("run-recovery", "source-recovery");

            releaseSourceRecovery.countDown();
            startup.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSourceRecovery.countDown();
        }

        assertThat(intake.isRunning()).isTrue();
        assertThat(events).containsExactly("run-recovery", "source-recovery", "intake-start");
    }

    @Test
    void leavesIntakeStoppedWhenRunRecoveryFails() {
        var sourceRecoveryCalls = new ArrayList<String>();
        var intake = new RecordingLifecycle(new ArrayList<>());
        var coordinator = new IngestionStartupCoordinator(
                () -> {
                    throw new IllegalStateException("run recovery failed");
                },
                () -> {
                    sourceRecoveryCalls.add("called");
                    return List.of();
                },
                intake);

        assertThatThrownBy(() -> coordinator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run recovery failed");

        assertThat(sourceRecoveryCalls).isEmpty();
        assertThat(intake.isRunning()).isFalse();
    }

    @Test
    void leavesIntakeStoppedWhenSourceRecoveryFails() {
        var intake = new RecordingLifecycle(new ArrayList<>());
        var coordinator = new IngestionStartupCoordinator(
                () -> { },
                () -> {
                    throw new IllegalStateException("source recovery failed");
                },
                intake);

        assertThatThrownBy(() -> coordinator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source recovery failed");

        assertThat(intake.isRunning()).isFalse();
    }

    @Test
    void rejectsAnAlreadyRunningIntakeBeforeRecovery() {
        var events = new ArrayList<String>();
        var intake = new RecordingLifecycle(events);
        intake.start();
        var coordinator = new IngestionStartupCoordinator(
                () -> events.add("run-recovery"),
                () -> List.of(),
                intake);

        assertThatThrownBy(() -> coordinator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must remain stopped");

        assertThat(events).containsExactly("intake-start");
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
}
