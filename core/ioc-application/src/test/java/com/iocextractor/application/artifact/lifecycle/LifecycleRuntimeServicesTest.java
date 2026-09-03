package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleActivationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleControlStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleHistoryStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleReconciliationStore;
import com.iocextractor.platform.events.ControlEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
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
    void projectionConvergenceSkipsCleanStateAndAcknowledgesTheObservedGeneration() {
        List<ProjectionAcknowledgement> acknowledgements = new ArrayList<>();
        ArtifactProjectionWorkStore work = new ArtifactProjectionWorkStore() {
            @Override
            public ArtifactProjectionState load(String artifactName) {
                return artifactName.equals("clean")
                        ? state(artifactName, 1, 1)
                        : state(artifactName, 2, 1);
            }

            @Override
            public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
                acknowledgements.add(acknowledgement);
                return true;
            }

            @Override
            public boolean recordFailure(
                    String artifactName,
                    ProjectionGeneration expectedGeneration,
                    String failureCode) {
                throw new AssertionError("successful convergence must not record failure");
            }
        };
        List<ArtifactProjectionCommand> commands = new ArrayList<>();
        var service = new ArtifactProjectionConvergenceService(
                List.of("clean", "masks"), work, command -> {
                    commands.add(command);
                    return ArtifactProjectionResult.clean(2);
                });

        ArtifactProjectionConvergenceResult result = service.convergePending();

        assertThat(result.projected()).isOne();
        assertThat(result.stillPending()).isZero();
        assertThat(result.projectedArtifacts()).containsExactly("masks");
        assertThat(commands).singleElement().satisfies(command -> {
            assertThat(command.runId()).isEqualTo("lifecycle-projection-masks-g2");
            assertThat(command.artifactName()).isEqualTo("masks");
        });
        assertThat(acknowledgements).singleElement().satisfies(acknowledgement ->
                assertThat(acknowledgement.installedGeneration())
                        .isEqualTo(new ProjectionGeneration(2)));
    }

    @Test
    void projectionConvergencePreservesEveryFailureAndFailureJournalError() {
        var firstFailure = new IllegalStateException("first projection failed");
        var secondFailure = new IllegalArgumentException("second projection failed");
        var journalFailure = new IllegalStateException("failure journal unavailable");
        List<String> recordedFailures = new ArrayList<>();
        ArtifactProjectionWorkStore work = new ArtifactProjectionWorkStore() {
            @Override
            public ArtifactProjectionState load(String artifactName) {
                return state(artifactName, artifactName.equals("first") ? 3 : 4, 1);
            }

            @Override
            public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
                throw new AssertionError("failed projection must not be acknowledged");
            }

            @Override
            public boolean recordFailure(
                    String artifactName,
                    ProjectionGeneration expectedGeneration,
                    String failureCode) {
                recordedFailures.add(artifactName + ":" + expectedGeneration.value() + ":" + failureCode);
                if (artifactName.equals("second")) {
                    throw journalFailure;
                }
                return true;
            }
        };
        var service = new ArtifactProjectionConvergenceService(
                List.of("first", "second"), work, command -> {
                    if (command.artifactName().equals("first")) {
                        throw firstFailure;
                    }
                    throw secondFailure;
                });

        assertThatThrownBy(service::convergePending)
                .isSameAs(firstFailure)
                .satisfies(failure -> {
                    assertThat(failure.getSuppressed()).containsExactly(secondFailure);
                    assertThat(secondFailure.getSuppressed()).containsExactly(journalFailure);
                });
        assertThat(recordedFailures).containsExactly(
                "first:3:LIFECYCLE.PROJECTION_FAILED",
                "second:4:LIFECYCLE.PROJECTION_FAILED");
    }

    @Test
    void projectionConvergenceRejectsAnAmbiguousArtifactCatalog() {
        ArtifactProjectionWorkStore work = new ArtifactProjectionWorkStore() {
            @Override
            public ArtifactProjectionState load(String artifactName) {
                throw new AssertionError();
            }

            @Override
            public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
                throw new AssertionError();
            }

            @Override
            public boolean recordFailure(
                    String artifactName,
                    ProjectionGeneration expectedGeneration,
                    String failureCode) {
                throw new AssertionError();
            }
        };
        ArtifactProjection projection = command -> {
            throw new AssertionError();
        };

        assertThatThrownBy(() -> new ArtifactProjectionConvergenceService(
                List.of("masks", "masks"), work, projection))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique non-blank");
        assertThatThrownBy(() -> new ArtifactProjectionConvergenceService(
                java.util.Arrays.asList("masks", null), work, projection))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique non-blank");
        assertThatThrownBy(() -> new ArtifactProjectionConvergenceService(
                List.of(" "), work, projection))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique non-blank");
    }

    @Test
    void admission_opens_only_after_active_reconciliation_and_projection_convergence() {
        LifecycleControlState active = LifecycleControlState.disabledCompatible()
                .beginActivation("fixed-12h-v1")
                .completeActivation(AS_OF);
        LifecycleControlStore control = new FixedControlStore(active);
        AtomicBoolean reconciled = new AtomicBoolean();
        AtomicBoolean projected = new AtomicBoolean();
        AtomicInteger activationChecks = new AtomicInteger();
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        AtomicBoolean callback = new AtomicBoolean();
        admission.whenAdmitted(() -> callback.set(true));
        var service = new LifecycleAdmissionService(
                control,
                () -> AS_OF,
                activationChecks::incrementAndGet,
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
        assertThat(activationChecks).hasValue(1);
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
        assertThat(activeCalls).hasValue(1);
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

    @Test
    void activation_is_one_way_and_resumes_after_projection_failure() {
        MutableControlStore control = new MutableControlStore(LifecycleControlState.disabledCompatible());
        AtomicInteger batches = new AtomicInteger();
        LifecycleActivationStore activation = new LifecycleActivationStore() {
            @Override
            public boolean hasLegacyRecords() {
                return true;
            }

            @Override
            public LifecycleActivationBatchResult expireLegacyBatch(
                    String artifactName, EffectiveTime activationAsOf, int batchSize) {
                assertThat(artifactName).isEqualTo("masks");
                assertThat(activationAsOf).isEqualTo(AS_OF);
                assertThat(batchSize).isEqualTo(2);
                return batches.getAndIncrement() == 0
                        ? new LifecycleActivationBatchResult("masks", 2, true)
                        : new LifecycleActivationBatchResult("masks", 1, false);
            }
        };
        AtomicInteger projections = new AtomicInteger();
        var service = new LifecycleActivationService(
                List.of("masks"), control, activation,
                () -> {
                    if (projections.getAndIncrement() == 0) {
                        throw new IllegalStateException("projection unavailable");
                    }
                    return new ArtifactProjectionConvergenceResult(1, 0, List.of("masks"));
                },
                () -> AS_OF,
                new LifecycleActivationPolicy(
                        true, "record-validity:fixed:v1", ExistingRecordsActivationPolicy.EXPIRE),
                2);

        assertThatThrownBy(service::resume)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection unavailable");
        assertThat(control.load().activationState()).isEqualTo(LifecycleActivationState.ACTIVATING);

        service.resume();

        assertThat(control.load().activationState()).isEqualTo(LifecycleActivationState.ACTIVE);
        assertThat(batches).hasValue(3);
        assertThatThrownBy(() -> new LifecycleActivationService(
                List.of("masks"), control, activation,
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF,
                LifecycleActivationPolicy.disabled(),
                2).resume())
                .isInstanceOf(LifecyclePolicyMismatchException.class)
                .hasMessageContaining("cannot be disabled");
    }

    @Test
    void activation_requires_the_explicit_legacy_expiry_policy() {
        var service = new LifecycleActivationService(
                List.of("masks"),
                new MutableControlStore(LifecycleControlState.disabledCompatible()),
                new LifecycleActivationStore() {
                    @Override
                    public boolean hasLegacyRecords() {
                        return true;
                    }

                    @Override
                    public LifecycleActivationBatchResult expireLegacyBatch(
                            String artifactName, EffectiveTime activationAsOf, int batchSize) {
                        throw new AssertionError("activation must stop before mutation");
                    }
                },
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF,
                new LifecycleActivationPolicy(
                        true, "record-validity:fixed:v1", ExistingRecordsActivationPolicy.REJECT),
                10);

        assertThatThrownBy(service::resume)
                .isInstanceOf(LifecyclePolicyMismatchException.class)
                .hasMessageContaining("existing-records=expire");
    }

    @Test
    void disabledAndActiveActivationStatesAreIdempotentForTheirConfiguredPolicy() {
        var activation = noLegacyActivation();
        new LifecycleActivationService(
                List.of("masks"),
                new FixedControlStore(LifecycleControlState.disabledCompatible()),
                activation,
                () -> {
                    throw new AssertionError("disabled validity must not converge projections");
                },
                () -> AS_OF,
                LifecycleActivationPolicy.disabled(),
                10).resume();

        LifecycleControlState active = LifecycleControlState.disabledCompatible()
                .beginActivation("record-validity:fixed:v1")
                .completeActivation(AS_OF);
        new LifecycleActivationService(
                List.of("masks"),
                new FixedControlStore(active),
                activation,
                () -> {
                    throw new AssertionError("active validity must not restart activation");
                },
                () -> AS_OF,
                fixedActivationPolicy(),
                10).resume();

        assertThatThrownBy(() -> new LifecycleActivationService(
                List.of("masks"),
                new FixedControlStore(active),
                activation,
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF,
                new LifecycleActivationPolicy(
                        true, "record-validity:fixed:v2", ExistingRecordsActivationPolicy.EXPIRE),
                10).resume())
                .isInstanceOf(LifecyclePolicyMismatchException.class)
                .hasMessageContaining("incompatible");
    }

    @Test
    void activationAdoptsConcurrentBeginAndCompletion() {
        LifecycleControlStore concurrentBegin = new LifecycleControlStore() {
            private LifecycleControlState state = LifecycleControlState.disabledCompatible();

            @Override
            public LifecycleControlState load() {
                return state;
            }

            @Override
            public boolean compareAndSet(LifecycleControlState expected, LifecycleControlState update) {
                state = update.completeActivation(AS_OF);
                return false;
            }
        };
        new LifecycleActivationService(
                List.of("masks"), concurrentBegin, noLegacyActivation(),
                () -> {
                    throw new AssertionError("concurrent completion needs no local convergence");
                },
                () -> AS_OF,
                fixedActivationPolicy(),
                10).resume();

        LifecycleControlState activating = LifecycleControlState.disabledCompatible()
                .beginActivation("record-validity:fixed:v1");
        LifecycleControlStore concurrentCompletion = new LifecycleControlStore() {
            private LifecycleControlState state = activating;

            @Override
            public LifecycleControlState load() {
                return state;
            }

            @Override
            public boolean compareAndSet(LifecycleControlState expected, LifecycleControlState update) {
                state = update;
                return false;
            }
        };
        new LifecycleActivationService(
                List.of(), concurrentCompletion, noLegacyActivation(),
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF,
                fixedActivationPolicy(),
                10).resume();

        MutableControlStore completedDuringProjection = new MutableControlStore(activating);
        new LifecycleActivationService(
                List.of(), completedDuringProjection, noLegacyActivation(),
                () -> {
                    LifecycleControlState current = completedDuringProjection.load();
                    completedDuringProjection.compareAndSet(current, current.completeActivation(AS_OF));
                    return new ArtifactProjectionConvergenceResult(0, 0, List.of());
                },
                () -> AS_OF,
                fixedActivationPolicy(),
                10).resume();
        assertThat(completedDuringProjection.load().activationState())
                .isEqualTo(LifecycleActivationState.ACTIVE);
    }

    @Test
    void activationFailsClosedWhenACompletionRaceDoesNotBecomeActive() {
        LifecycleControlState activating = LifecycleControlState.disabledCompatible()
                .beginActivation("record-validity:fixed:v1");
        LifecycleControlStore lostCompletion = new LifecycleControlStore() {
            @Override
            public LifecycleControlState load() {
                return activating;
            }

            @Override
            public boolean compareAndSet(LifecycleControlState expected, LifecycleControlState update) {
                return false;
            }
        };
        var service = new LifecycleActivationService(
                List.of(), lostCompletion, noLegacyActivation(),
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF,
                fixedActivationPolicy(),
                10);

        assertThatThrownBy(service::resume)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completion lost");
    }

    @Test
    void activationRejectsInvalidCatalogAndBatchConfiguration() {
        var control = new FixedControlStore(LifecycleControlState.disabledCompatible());
        var activation = noLegacyActivation();
        var policy = fixedActivationPolicy();

        assertThatThrownBy(() -> new LifecycleActivationService(
                List.of("masks", "masks"), control, activation,
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF, policy, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique and non-blank");
        assertThatThrownBy(() -> new LifecycleActivationService(
                java.util.Arrays.asList("masks", null), control, activation,
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF, policy, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique and non-blank");
        assertThatThrownBy(() -> new LifecycleActivationService(
                List.of(" "), control, activation,
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF, policy, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique and non-blank");
        assertThatThrownBy(() -> new LifecycleActivationService(
                List.of("masks"), control, activation,
                () -> new ArtifactProjectionConvergenceResult(0, 0, List.of()),
                () -> AS_OF, policy, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size");
    }

    @Test
    void lifecycleHistoryRetentionPurgesHistoryAndReceiptsIndependently() {
        Map<String, LifecycleHistoryStore.HistoryPurgeResult> batches = new LinkedHashMap<>();
        batches.put("masks", new LifecycleHistoryStore.HistoryPurgeResult("masks", 0, false));
        batches.put("hashes", new LifecycleHistoryStore.HistoryPurgeResult("hashes", 2, true));
        List<EffectiveTime> cutoffs = new ArrayList<>();
        LifecycleHistoryStore history = (artifactName, cutoff, batchSize) -> {
            cutoffs.add(cutoff);
            assertThat(batchSize).isEqualTo(10);
            return batches.get(artifactName);
        };

        LifecycleHistoryRetentionResult historyOnly = new LifecycleHistoryRetentionService(
                List.of("masks", "hashes"), history, () -> AS_OF, Duration.ofDays(30), 10).run();

        assertThat(historyOnly.purged()).isEqualTo(2);
        assertThat(historyOnly.moreEligible()).isTrue();
        assertThat(historyOnly.purgedReceipts()).isZero();
        assertThat(historyOnly.purgedByArtifact()).containsExactlyEntriesOf(Map.of("hashes", 2));
        assertThat(cutoffs).containsOnly(EffectiveTime.at(AS_OF.value().minus(Duration.ofDays(30))));

        ConfirmationReceiptStore receipts = new ConfirmationReceiptStore() {
            @Override
            public Optional<ConfirmationReceiptSnapshot> findComplete(
                    String sourceKey, String processingPolicyFingerprint, EffectiveTime asOf) {
                throw new AssertionError();
            }

            @Override
            public PurgeResult purgeExpired(EffectiveTime asOf, int batchSize) {
                assertThat(asOf).isEqualTo(AS_OF);
                assertThat(batchSize).isEqualTo(5);
                return new PurgeResult(3, true);
            }
        };
        LifecycleHistoryRetentionResult withReceipts = new LifecycleHistoryRetentionService(
                List.of("masks"),
                (artifactName, cutoff, batchSize) ->
                        new LifecycleHistoryStore.HistoryPurgeResult(artifactName, 0, false),
                () -> AS_OF,
                Duration.ofDays(1),
                5,
                receipts).run();

        assertThat(withReceipts.purged()).isEqualTo(3);
        assertThat(withReceipts.moreEligible()).isTrue();
        assertThat(withReceipts.purgedReceipts()).isEqualTo(3);
        assertThat(withReceipts.purgedByArtifact()).isEmpty();
    }

    @Test
    void lifecycleHistoryRetentionRejectsUnboundedOrAmbiguousConfiguration() {
        LifecycleHistoryStore history = (artifactName, cutoff, batchSize) ->
                new LifecycleHistoryStore.HistoryPurgeResult(artifactName, 0, false);

        assertThatThrownBy(() -> new LifecycleHistoryRetentionService(
                List.of("masks"), history, () -> AS_OF, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionService(
                List.of("masks"), history, () -> AS_OF, Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionService(
                List.of("masks"), history, () -> AS_OF, Duration.ofDays(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionService(
                List.of("masks", "masks"), history, () -> AS_OF, Duration.ofDays(1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique non-blank");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionService(
                java.util.Arrays.asList("masks", null), history,
                () -> AS_OF, Duration.ofDays(1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique non-blank");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionService(
                List.of(" "), history, () -> AS_OF, Duration.ofDays(1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique non-blank");
    }

    @Test
    void complete_current_policy_receipt_replays_with_a_new_observation() {
        var priorReceipt = new ConfirmationReceiptSnapshot(
                new ConfirmationReceiptId("receipt-prior"),
                "source-a",
                "policy-a",
                List.of(new ConfirmationReceiptArtifact("masks", List.of("mask"), List.of())));
        ConfirmationReceiptStore receipts = new ConfirmationReceiptStore() {
            @Override
            public Optional<ConfirmationReceiptSnapshot> findComplete(
                    String sourceKey, String processingPolicyFingerprint, EffectiveTime asOf) {
                assertThat(sourceKey).isEqualTo("source-a");
                assertThat(processingPolicyFingerprint).isEqualTo("policy-a");
                assertThat(asOf).isEqualTo(AS_OF);
                return Optional.of(priorReceipt);
            }

            @Override
            public PurgeResult purgeExpired(EffectiveTime asOf, int batchSize) {
                throw new UnsupportedOperationException();
            }
        };
        List<CanonicalArtifactConfirmation> confirmations = new ArrayList<>();
        ObservationId currentObservation = new ObservationId("observation-current");
        var result = new ConfirmationReceiptReplayService(
                receipts,
                confirmation -> {
                    confirmations.add(confirmation);
                    return new LifecycleWriteResult(
                            currentObservation, confirmation.artifactName(), AS_OF,
                            0, 0, 0, 7, new ProjectionGeneration(0), false);
                },
                () -> AS_OF).replay(new ConfirmationReceiptReplayCommand(
                        new LifecycleWriteContext(
                                currentObservation,
                                "source-a",
                                new ConfirmationReceiptContext(
                                        new ConfirmationReceiptId("receipt-current"),
                                        "policy-a",
                                        1,
                                        java.time.Duration.ofDays(30)))));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().insertedPerArtifact()).isEqualTo(Map.of("masks", 0));
        assertThat(confirmations).singleElement().satisfies(confirmation -> {
            assertThat(confirmation.observationId()).isEqualTo(currentObservation);
            assertThat(confirmation.receipt().id().value()).isEqualTo("receipt-current");
            assertThat(confirmation.records()).isEmpty();
        });
    }

    private ExpiryBatchResult batch(String artifact, int expired, boolean more, long generation) {
        return new ExpiryBatchResult(
                artifact, AS_OF, expired, more, 7, new ProjectionGeneration(generation));
    }

    private ArtifactProjectionState state(String artifact, long required, long projected) {
        return new ArtifactProjectionState(
                artifact, new ProjectionGeneration(required), new ProjectionGeneration(projected));
    }

    private LifecycleActivationPolicy fixedActivationPolicy() {
        return new LifecycleActivationPolicy(
                true, "record-validity:fixed:v1", ExistingRecordsActivationPolicy.EXPIRE);
    }

    private LifecycleActivationStore noLegacyActivation() {
        return new LifecycleActivationStore() {
            @Override
            public boolean hasLegacyRecords() {
                return false;
            }

            @Override
            public LifecycleActivationBatchResult expireLegacyBatch(
                    String artifactName,
                    EffectiveTime activationAsOf,
                    int batchSize) {
                return new LifecycleActivationBatchResult(artifactName, 0, false);
            }
        };
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

    private static final class MutableControlStore implements LifecycleControlStore {

        private LifecycleControlState state;

        private MutableControlStore(LifecycleControlState state) {
            this.state = state;
        }

        @Override
        public LifecycleControlState load() {
            return state;
        }

        @Override
        public boolean compareAndSet(LifecycleControlState expected, LifecycleControlState update) {
            if (!state.equals(expected)) {
                return false;
            }
            state = update;
            return true;
        }
    }
}
