package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleControlStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleReconciliationStore;
import com.iocextractor.platform.events.ControlEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleRuntimeServicesTest {

    private static final EffectiveTime AS_OF = EffectiveTime.at(
            Instant.parse("2026-08-16T02:00:00Z"));

    @Test
    void reconciliation_drains_bounded_batches_and_emits_one_projection_hint_per_artifact() {
        Deque<ExpiryBatchResult> batches = new ArrayDeque<>(List.of(
                batch("masks", 2, true, 1),
                batch("masks", 1, false, 2),
                batch("hashes", 0, false, 0)));
        ExpiredArtifactStore expired = new ExpiredArtifactStore() {
            @Override
            public Optional<LifecycleDeadline> nearestDeadline() {
                return Optional.empty();
            }

            @Override
            public ExpiryBatchResult expireDue(String artifactName,
                                               EffectiveTime cycleAsOf,
                                               int batchSize) {
                ExpiryBatchResult result = batches.removeFirst();
                assertThat(result.artifactName()).isEqualTo(artifactName);
                assertThat(cycleAsOf).isEqualTo(AS_OF);
                assertThat(batchSize).isEqualTo(2);
                return result;
            }
        };
        RecordingCycles cycles = new RecordingCycles();
        List<ControlEvent> events = new ArrayList<>();
        AtomicInteger yields = new AtomicInteger();
        var service = new LifecycleReconciliationService(
                List.of("masks", "hashes"), expired, cycles, () -> AS_OF,
                events::add, 2, yields::incrementAndGet);

        LifecycleReconciliationResult result = service.reconcile();

        assertThat(result.expired()).isEqualTo(3);
        assertThat(result.batches()).isEqualTo(3);
        assertThat(result.affectedArtifacts()).containsExactly("masks");
        assertThat(yields).hasValue(1);
        assertThat(cycles.recorded).containsExactly(2, 1);
        assertThat(cycles.completedExpired).isEqualTo(3);
        assertThat(events).singleElement().isInstanceOf(MutableArtifactProjectionRequired.class);
    }

    @Test
    void projection_acknowledgement_never_claims_a_newer_generation() {
        AtomicInteger projections = new AtomicInteger();
        ArtifactProjectionWorkStore work = new ArtifactProjectionWorkStore() {
            @Override
            public ArtifactProjectionState load(String artifactName) {
                return state(artifactName, 2, 1);
            }

            @Override
            public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
                assertThat(acknowledgement.expectedRequiredGeneration())
                        .isEqualTo(new ProjectionGeneration(2));
                return false;
            }

            @Override
            public boolean recordFailure(String artifactName,
                                         ProjectionGeneration expectedGeneration,
                                         String failureCode) {
                return false;
            }
        };
        ArtifactProjection projection = command -> {
            projections.incrementAndGet();
            return ArtifactProjectionResult.clean(5);
        };
        var service = new ArtifactProjectionConvergenceService(
                List.of("masks"), work, projection);

        ArtifactProjectionConvergenceResult result = service.convergePending();

        assertThat(projections).hasValue(1);
        assertThat(result.projected()).isZero();
        assertThat(result.stillPending()).isOne();
    }

    @Test
    void admission_opens_only_after_active_reconciliation_and_projection_convergence() {
        LifecycleControlState active = LifecycleControlState.disabledCompatible()
                .beginActivation("fixed-12h-v1")
                .completeActivation(AS_OF);
        LifecycleControlStore control = new FixedControlStore(active);
        AtomicBoolean reconciled = new AtomicBoolean();
        AtomicBoolean projected = new AtomicBoolean();
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicBoolean callback = new AtomicBoolean();
        admission.whenAdmitted(() -> callback.set(true));
        var service = new LifecycleAdmissionService(
                control,
                () -> AS_OF,
                () -> {
                    throw new AssertionError("active state must not resume activation");
                },
                () -> {
                    reconciled.set(true);
                    return new LifecycleReconciliationResult(
                            new LifecycleReconcileCycleId(1), AS_OF, 2, 1, List.of("masks"));
                },
                () -> {
                    assertThat(reconciled).isTrue();
                    projected.set(true);
                    return new ArtifactProjectionConvergenceResult(1, 0, List.of("masks"));
                },
                admission);

        LifecycleAdmissionResult result = service.prepare();

        assertThat(result.lifecycleActive()).isTrue();
        assertThat(result.expired()).isEqualTo(2);
        assertThat(result.projectionsConverged()).isOne();
        assertThat(projected).isTrue();
        assertThat(callback).isTrue();
        assertThat(admission.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.ADMITTED);
        assertThat(service.prepare()).isSameAs(result);
    }

    @Test
    void admission_fails_closed_and_retries_an_unsuccessful_open_callback() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicInteger attempts = new AtomicInteger();
        admission.whenAdmitted(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("scheduler unavailable");
            }
        });
        var service = new LifecycleAdmissionService(
                new FixedControlStore(LifecycleControlState.disabledCompatible()),
                () -> AS_OF,
                () -> { },
                () -> new LifecycleReconciliationResult(
                        new LifecycleReconcileCycleId(1), AS_OF, 0, 0, List.of()),
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                admission);

        assertThatThrownBy(service::prepare)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scheduler unavailable");
        assertThat(admission.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.FAILED);

        assertThat(service.prepare().lifecycleActive()).isFalse();
        assertThat(attempts).hasValue(2);
        assertThat(admission.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.ADMITTED);
    }

    @Test
    void disabled_compatible_admission_validates_time_without_running_active_maintenance() {
        AtomicInteger activeCalls = new AtomicInteger();
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        var service = new LifecycleAdmissionService(
                new FixedControlStore(LifecycleControlState.disabledCompatible()),
                () -> AS_OF,
                activeCalls::incrementAndGet,
                () -> {
                    activeCalls.incrementAndGet();
                    throw new AssertionError();
                },
                () -> {
                    activeCalls.incrementAndGet();
                    throw new AssertionError();
                },
                admission);

        LifecycleAdmissionResult result = service.prepare();

        assertThat(result.activationState()).isEqualTo(LifecycleActivationState.DISABLED_COMPATIBLE);
        assertThat(activeCalls).hasValue(0);
        assertThat(admission.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.ADMITTED);
    }

    @Test
    void post_commit_events_are_lossy_hints_and_replays_emit_nothing() {
        List<ControlEvent> events = new ArrayList<>();
        ObservationId observation = new ObservationId("observation-1");
        var inserted = new LifecycleWriteResult(
                observation, "masks", AS_OF, 1, 0, 0,
                1, new ProjectionGeneration(1), false);
        var writer = new EventPublishingCanonicalArtifactWriter(ignored -> inserted, events::add);

        assertThat(writer.confirm(null)).isEqualTo(inserted);
        assertThat(events).hasSize(2)
                .anyMatch(CanonicalDeadlineScheduleChanged.class::isInstance)
                .anyMatch(MutableArtifactProjectionRequired.class::isInstance);

        events.clear();
        var replay = new LifecycleWriteResult(
                observation, "masks", AS_OF, 1, 0, 0,
                1, new ProjectionGeneration(1), true);
        new EventPublishingCanonicalArtifactWriter(ignored -> replay, events::add).confirm(null);
        assertThat(events).isEmpty();

        var resilient = new EventPublishingCanonicalArtifactWriter(
                ignored -> inserted,
                ignored -> {
                    throw new IllegalStateException("event adapter unavailable");
                });
        assertThat(resilient.confirm(null)).isEqualTo(inserted);
    }

    private ExpiryBatchResult batch(String artifact, int expired, boolean more, long generation) {
        return new ExpiryBatchResult(
                artifact, AS_OF, expired, more, 7, new ProjectionGeneration(generation));
    }

    private ArtifactProjectionState state(String artifact, long required, long projected) {
        return new ArtifactProjectionState(
                artifact, new ProjectionGeneration(required), new ProjectionGeneration(projected));
    }

    private static final class RecordingCycles implements LifecycleReconciliationStore {

        private final List<Integer> recorded = new ArrayList<>();
        private int completedExpired;

        @Override
        public int failInterrupted(EffectiveTime recoveredAt, String failureCode) {
            return 0;
        }

        @Override
        public LifecycleReconcileCycleId start(EffectiveTime cycleAsOf) {
            return new LifecycleReconcileCycleId(1);
        }

        @Override
        public void recordBatch(LifecycleReconcileCycleId cycleId, int expired) {
            recorded.add(expired);
        }

        @Override
        public void complete(LifecycleReconcileCycleId cycleId,
                             EffectiveTime completedAt,
                             int expired,
                             int affectedArtifacts) {
            completedExpired = expired;
            assertThat(affectedArtifacts).isOne();
        }

        @Override
        public void fail(LifecycleReconcileCycleId cycleId,
                         EffectiveTime failedAt,
                         String failureCode) {
            throw new AssertionError("successful cycle must not fail");
        }
    }

    private record FixedControlStore(LifecycleControlState state) implements LifecycleControlStore {

        @Override
        public LifecycleControlState load() {
            return state;
        }

        @Override
        public boolean compareAndSet(LifecycleControlState expected, LifecycleControlState update) {
            throw new UnsupportedOperationException();
        }
    }
}
