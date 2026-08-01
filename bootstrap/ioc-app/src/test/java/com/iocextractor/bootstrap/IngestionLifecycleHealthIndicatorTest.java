package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.IngestionLifecycleState;
import com.iocextractor.platform.concurrent.KeyedExecutionGuard;
import com.iocextractor.platform.concurrent.KeyedExecutionGuardSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.Lifecycle;

import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionLifecycleHealthIndicatorTest {

    private static final Instant STARTED = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-08-02T00:00:01Z");

    @Test
    void reportsDownUntilRecoveryCompletesAndIntakeRuns() {
        var state = new IngestionLifecycleState();
        var intake = new RecordingLifecycle();
        var indicator = new IngestionLifecycleHealthIndicator(state, intake,
                guard(() -> new KeyedExecutionGuardSnapshot(1, 1, 2)));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        state.recoveryStarted(STARTED);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        state.running(COMPLETED, 2, 3);
        intake.start();

        assertThat(indicator.health()).satisfies(health -> {
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("phase", "RUNNING")
                    .containsEntry("intakeRunning", true)
                    .containsEntry("recoveredRuns", 2)
                    .containsEntry("recoveredSources", 3)
                    .containsEntry("activeSourceKeys", 1)
                    .containsEntry("executing", 1)
                    .containsEntry("waiting", 2)
                    .containsEntry("recoveryStartedAt", STARTED.toString())
                    .containsEntry("recoveryCompletedAt", COMPLETED.toString())
                    .doesNotContainKey("failure");
        });
    }

    @Test
    void reportsFailedRecoveryWithoutSourceKeys() {
        var state = new IngestionLifecycleState();
        state.recoveryStarted(STARTED);
        state.failed(COMPLETED, new IllegalStateException("ledger unavailable"));
        var indicator = new IngestionLifecycleHealthIndicator(
                state, new RecordingLifecycle(), guard(KeyedExecutionGuardSnapshot::empty));

        assertThat(indicator.health()).satisfies(health -> {
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("phase", "FAILED")
                    .containsEntry("failure", "IllegalStateException")
                    .doesNotContainKey("sourceKey");
            assertThat(health.getDetails().values()).doesNotContain("ledger unavailable");
        });
    }

    private KeyedExecutionGuard guard(Supplier<KeyedExecutionGuardSnapshot> snapshot) {
        return new KeyedExecutionGuard() {
            @Override
            public <T> T execute(com.iocextractor.platform.concurrent.WorkKey key, Supplier<T> work) {
                return work.get();
            }

            @Override
            public KeyedExecutionGuardSnapshot snapshot() {
                return snapshot.get();
            }
        };
    }

    private static final class RecordingLifecycle implements Lifecycle {
        private boolean running;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
