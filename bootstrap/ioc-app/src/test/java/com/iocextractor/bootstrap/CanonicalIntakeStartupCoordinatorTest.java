package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.IngestionLifecycleState;
import com.iocextractor.adapter.in.ingest.IngestionStartupObserver;
import com.iocextractor.application.artifact.IngestRun;
import com.iocextractor.application.artifact.IngestRunRecoveryService;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.out.artifact.RunLedger;
import org.junit.jupiter.api.Test;
import org.springframework.context.Lifecycle;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalIntakeStartupCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void opensOrdinaryIntakeOnlyAfterBothRecoveryDomainsAndImportRuntimeStart() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle intake = new RecordingLifecycle(events);
        RecordingImportRuntime imports = new RecordingImportRuntime(events, false);
        IngestionLifecycleState state = new IngestionLifecycleState();
        CanonicalIntakeStartupCoordinator coordinator = coordinator(events, intake, imports, state);

        coordinator.run(null);

        assertThat(coordinator.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(events).containsExactly(
                "recovery-started", "ordinary-run-recovery", "ordinary-source-recovery",
                "lifecycle-admission", "import-recovery", "import-start",
                "intake-start", "recovery-completed");
        assertThat(intake.isRunning()).isTrue();
        assertThat(state.snapshot().phase()).isEqualTo(IngestionLifecycleState.Phase.RUNNING);
    }

    @Test
    void importRecoveryFailureClosesBothRuntimesAndLeavesIntakeStopped() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle intake = new RecordingLifecycle(events);
        RecordingImportRuntime imports = new RecordingImportRuntime(events, true);
        IngestionLifecycleState state = new IngestionLifecycleState();
        CanonicalIntakeStartupCoordinator coordinator = coordinator(events, intake, imports, state);

        assertThatThrownBy(() -> coordinator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("import recovery failed");

        assertThat(events).containsExactly(
                "recovery-started", "ordinary-run-recovery", "ordinary-source-recovery",
                "lifecycle-admission", "import-recovery", "intake-stop",
                "import-close", "recovery-failed");
        assertThat(intake.isRunning()).isFalse();
        assertThat(state.snapshot().phase()).isEqualTo(IngestionLifecycleState.Phase.FAILED);
    }

    private CanonicalIntakeStartupCoordinator coordinator(
            List<String> events,
            Lifecycle intake,
            DataframeImportRuntimeLifecycle imports,
            IngestionLifecycleState state) {
        IngestRunRecoveryService ordinaryRuns = new IngestRunRecoveryService(
                new EmptyRunLedger(events),
                ignored -> {
                    throw new AssertionError("no projection expected");
                },
                ignored -> {
                });
        return new CanonicalIntakeStartupCoordinator(
                ordinaryRuns,
                () -> {
                    events.add("ordinary-source-recovery");
                    return List.of();
                },
                () -> {
                    events.add("lifecycle-admission");
                    return new LifecycleAdmissionResult(
                            LifecycleActivationState.DISABLED_COMPATIBLE,
                            EffectiveTime.at(NOW), 0, 0);
                },
                intake,
                imports,
                state,
                new RecordingObserver(events),
                CLOCK);
    }

    private static final class RecordingImportRuntime implements DataframeImportRuntimeLifecycle {
        private final List<String> events;
        private final boolean failRecovery;

        private RecordingImportRuntime(List<String> events, boolean failRecovery) {
            this.events = events;
            this.failRecovery = failRecovery;
        }

        @Override
        public RecoverDataframeImportsResult recoverBeforeIntake() {
            events.add("import-recovery");
            if (failRecovery) {
                throw new IllegalStateException("import recovery failed");
            }
            return new RecoverDataframeImportsResult(0, 0, 0);
        }

        @Override
        public void start() {
            events.add("import-start");
        }

        @Override
        public boolean recoveryComplete() {
            return !failRecovery;
        }

        @Override
        public void close() {
            events.add("import-close");
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

    private static final class RecordingObserver implements IngestionStartupObserver {
        private final List<String> events;

        private RecordingObserver(List<String> events) {
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

    private static final class EmptyRunLedger implements RunLedger {
        private final List<String> events;

        private EmptyRunLedger(List<String> events) {
            this.events = events;
        }

        @Override
        public IngestRun startIngest(String sourceKey, List<String> artifacts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDbCommitted(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markProjectionCompleted(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markCompleted(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markFailed(String runId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IngestRun> findIncompleteIngestRuns() {
            events.add("ordinary-run-recovery");
            return List.of();
        }
    }
}
