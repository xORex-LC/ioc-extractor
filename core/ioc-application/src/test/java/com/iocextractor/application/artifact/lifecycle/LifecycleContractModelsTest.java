package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactsChanged;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleHistoryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleContractModelsTest {

    private static final EffectiveTime NOW =
            EffectiveTime.at(Instant.parse("2026-08-16T00:00:00Z"));

    @Test
    void activation_is_one_way_and_idempotent_for_one_policy() {
        LifecycleControlState disabled = LifecycleControlState.disabledCompatible();

        LifecycleControlState activating = disabled.beginActivation("fixed-12h-v1");
        LifecycleControlState active = activating.completeActivation(NOW);

        assertThat(activating.activationState()).isEqualTo(LifecycleActivationState.ACTIVATING);
        assertThat(activating.beginActivation("fixed-12h-v1")).isSameAs(activating);
        assertThat(active.activationState()).isEqualTo(LifecycleActivationState.ACTIVE);
        assertThat(active.activatedAt()).contains(NOW);
        assertThat(active.completeActivation(
                EffectiveTime.at(NOW.value().plusSeconds(1)))).isSameAs(active);
        assertThatThrownBy(() -> active.beginActivation("fixed-24h-v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot change");
    }

    @Test
    void active_snapshot_rejects_a_due_record() {
        RecordLifecycle due = new RecordLifecycle(
                new LifecycleId(1),
                EffectiveTime.at(NOW.value().minusSeconds(60)),
                EffectiveTime.at(NOW.value().minusSeconds(60)),
                new LifecycleDeadline(NOW.value()));
        var record = new ActiveArtifactRecord(
                new ArtifactRowKey("row-1"),
                new ArtifactRow(Map.of("value", "example.test")),
                due);

        assertThatThrownBy(() -> new ActiveArtifactSnapshot(
                "masks", List.of("value"), List.of(record), 1,
                new ProjectionGeneration(1), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("due lifecycle");
    }

    @Test
    void projection_state_exposes_pending_work_and_rejects_future_acknowledgement() {
        var state = new ArtifactProjectionState(
                "masks", new ProjectionGeneration(3), new ProjectionGeneration(2));

        assertThat(state.pending()).isTrue();
        assertThatThrownBy(() -> new ArtifactProjectionState(
                "masks", new ProjectionGeneration(2), new ProjectionGeneration(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectionAcknowledgement(
                "masks", new ProjectionGeneration(2), new ProjectionGeneration(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void write_result_preserves_insert_driven_revision_semantics() {
        var result = new LifecycleWriteResult(
                new ObservationId("observation-1"), "masks", NOW,
                2, 3, 1, 7, new ProjectionGeneration(4), false);

        assertThat(result.publicRowsInserted()).isEqualTo(3);
        assertThat(result.confirmedRecords()).isEqualTo(6);

        assertThatThrownBy(() -> new LifecycleWriteResult(
                new ObservationId("observation-2"), "masks", NOW,
                1, 0, 0, 0, new ProjectionGeneration(1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive artifact revision");
    }

    @Test
    void lifecycle_control_shape_rejects_partial_active_state() {
        assertThatThrownBy(() -> new LifecycleControlState(
                1,
                LifecycleActivationState.ACTIVE,
                Optional.of("fixed-12h-v1"),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires policy and activation time");
    }

    @Test
    void lifecycleControlRejectsEveryContradictoryStateShape() {
        assertThatThrownBy(() -> new LifecycleControlState(
                -1, LifecycleActivationState.DISABLED_COMPATIBLE,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new LifecycleControlState(
                1, LifecycleActivationState.DISABLED_COMPATIBLE,
                Optional.of("fixed-12h-v1"), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot carry");
        assertThatThrownBy(() -> new LifecycleControlState(
                1, LifecycleActivationState.ACTIVATING,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires only");
        assertThatThrownBy(() -> new LifecycleControlState(
                1, LifecycleActivationState.ACTIVATING,
                Optional.of("fixed-12h-v1"), Optional.of(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires only");
        assertThatThrownBy(() -> new LifecycleControlState(
                1, LifecycleActivationState.ACTIVE,
                Optional.empty(), Optional.of(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires policy");
        assertThatThrownBy(() -> new LifecycleControlState(
                1, LifecycleActivationState.ACTIVATING,
                Optional.of(" "), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyFingerprint");
    }

    @Test
    void lifecycleControlRejectsInvalidTransitionArguments() {
        LifecycleControlState disabled = LifecycleControlState.disabledCompatible();

        assertThatThrownBy(() -> disabled.beginActivation(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> disabled.completeActivation(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not started");
        assertThatThrownBy(() -> disabled.beginActivation(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("fingerprint");
    }

    @Test
    void confirmation_receipt_context_requires_publishable_identity_and_bounds() {
        var id = new ConfirmationReceiptId("receipt-1");

        assertThat(new ConfirmationReceiptContext(id, "policy-v1", 2, Duration.ofDays(30)))
                .extracting(ConfirmationReceiptContext::expectedArtifacts,
                        ConfirmationReceiptContext::retention)
                .containsExactly(2, Duration.ofDays(30));
        assertThatThrownBy(() -> new ConfirmationReceiptId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> new ConfirmationReceiptContext(
                id, " ", 1, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> new ConfirmationReceiptContext(
                id, "policy-v1", 0, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact count");
        assertThatThrownBy(() -> new ConfirmationReceiptContext(
                id, "policy-v1", 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention");
    }

    @Test
    void admission_state_releases_deferred_work_and_exposes_fail_closed_state() {
        CanonicalDataAdmissionState state = new CanonicalDataAdmissionState();
        AtomicInteger callbacks = new AtomicInteger();

        assertThat(state.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.PENDING);
        state.whenAdmitted(callbacks::incrementAndGet);
        state.preparing();
        assertThat(state.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.PREPARING);

        LifecycleAdmissionResult result = new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, NOW, 2, 1);
        state.admitted(result);
        assertThat(state.snapshot().result()).isEqualTo(result);
        assertThat(callbacks).hasValue(1);

        state.whenAdmitted(callbacks::incrementAndGet);
        assertThat(callbacks).hasValue(2);
        state.failed(new IllegalStateException("sensitive detail"));
        assertThat(state.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.FAILED);
        assertThat(state.snapshot().failure()).isEqualTo("IllegalStateException");
    }

    @Test
    void admission_callbacks_remain_retryable_until_the_whole_batch_succeeds() {
        CanonicalDataAdmissionState state = new CanonicalDataAdmissionState();
        AtomicInteger attempts = new AtomicInteger();
        state.whenAdmitted(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("first attempt");
            }
        });
        LifecycleAdmissionResult result = new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, NOW, 0, 0);

        assertThatThrownBy(() -> state.admitted(result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("first attempt");
        state.admitted(result);

        assertThat(attempts).hasValue(2);
        assertThat(state.snapshot().phase()).isEqualTo(CanonicalDataAdmissionState.Phase.ADMITTED);
    }

    @Test
    void admission_snapshot_rejects_contradictory_payloads() {
        assertThatThrownBy(() -> new CanonicalDataAdmissionState.Snapshot(
                CanonicalDataAdmissionState.Phase.ADMITTED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a result");
        assertThatThrownBy(() -> new CanonicalDataAdmissionState.Snapshot(
                CanonicalDataAdmissionState.Phase.PENDING,
                new LifecycleAdmissionResult(
                        LifecycleActivationState.DISABLED_COMPATIBLE, NOW, 0, 0), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only admitted");
        assertThatThrownBy(() -> new CanonicalDataAdmissionState.Snapshot(
                CanonicalDataAdmissionState.Phase.FAILED, null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a failure");
        assertThatThrownBy(() -> new CanonicalDataAdmissionState.Snapshot(
                CanonicalDataAdmissionState.Phase.PREPARING, null, "failure"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only failed");
    }

    @Test
    void lifecycle_aggregate_results_reject_inconsistent_counters() {
        assertThat(new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, NOW, 0, 0).lifecycleActive()).isTrue();
        assertThat(new LifecycleAdmissionResult(
                LifecycleActivationState.DISABLED_COMPATIBLE, NOW, 0, 0).lifecycleActive()).isFalse();
        for (int[] counters : List.of(new int[] {-1, 0}, new int[] {0, -1})) {
            assertThatThrownBy(() -> new LifecycleAdmissionResult(
                    LifecycleActivationState.ACTIVE, NOW, counters[0], counters[1]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("counters");
        }

        assertThatThrownBy(() -> new LifecycleArtifactStatistics("masks", -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
        assertThatThrownBy(() -> new LifecycleArtifactStatistics("masks", 1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
        assertThatThrownBy(() -> new LifecycleArtifactStatistics(" ", 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactName");
    }

    @Test
    void history_and_reconciliation_totals_are_complete_and_non_negative() {
        LifecycleHistoryRetentionResult retained = new LifecycleHistoryRetentionResult(
                3, true, 1, Map.of("masks", 2));
        assertThat(retained.purged()).isEqualTo(3);
        assertThat(new LifecycleHistoryRetentionResult(2, false, Map.of("masks", 2))
                .purgedReceipts()).isZero();

        assertThatThrownBy(() -> new LifecycleHistoryRetentionResult(
                -1, false, 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionResult(
                0, false, -1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionResult(
                1, false, 0, Map.of("masks", -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new LifecycleHistoryRetentionResult(
                3, false, 0, Map.of("masks", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");

        LifecycleReconciliationResult changed = new LifecycleReconciliationResult(
                new LifecycleReconcileCycleId(1), NOW, 2, 1, List.of("masks"));
        assertThat(changed.changedMembership()).isTrue();
        assertThat(new LifecycleReconciliationResult(
                new LifecycleReconcileCycleId(2), NOW, 0, 0, List.of())
                .changedMembership()).isFalse();
        assertThatThrownBy(() -> new LifecycleReconciliationResult(
                new LifecycleReconcileCycleId(3), NOW, -1, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counters");
        assertThatThrownBy(() -> new LifecycleReconciliationResult(
                new LifecycleReconcileCycleId(4), NOW, 0, 0, List.of("masks")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot name artifacts");
    }

    @Test
    void lifecycle_write_and_expiry_results_enforce_revision_and_generation_rules() {
        for (int[] counts : List.of(
                new int[] {-1, 0, 0},
                new int[] {0, -1, 0},
                new int[] {0, 0, -1})) {
            assertThatThrownBy(() -> new LifecycleWriteResult(
                    new ObservationId("observation"), "masks", NOW,
                    counts[0], counts[1], counts[2], 1,
                    new ProjectionGeneration(1), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("counts");
        }
        assertThatThrownBy(() -> new LifecycleWriteResult(
                new ObservationId("observation"), "masks", NOW,
                0, 0, 0, -1, new ProjectionGeneration(0), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");

        assertThatThrownBy(() -> new ExpiryBatchResult(
                "masks", NOW, -1, false, 0, new ProjectionGeneration(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expired row count");
        assertThatThrownBy(() -> new ExpiryBatchResult(
                "masks", NOW, 0, false, -1, new ProjectionGeneration(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> new ExpiryBatchResult(
                "masks", NOW, 1, false, 0, new ProjectionGeneration(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection work");
        assertThat(new ExpiryBatchResult(
                "masks", NOW, 1, false, 7, new ProjectionGeneration(1)).expired())
                .isEqualTo(1);
    }

    @Test
    void active_snapshot_and_projection_result_require_complete_consistent_evidence() {
        RecordLifecycle activeLifecycle = new RecordLifecycle(
                new LifecycleId(1), NOW, NOW,
                new LifecycleDeadline(NOW.value().plusSeconds(60)));
        ActiveArtifactRecord record = new ActiveArtifactRecord(
                new ArtifactRowKey("row-1"),
                new ArtifactRow(Map.of("value", "example.test")), activeLifecycle);

        ActiveArtifactSnapshot snapshot = new ActiveArtifactSnapshot(
                "masks", List.of("value"), List.of(record), 0,
                new ProjectionGeneration(0), NOW);
        assertThat(snapshot.records()).containsExactly(record);
        assertThatThrownBy(() -> new ActiveArtifactSnapshot(
                "masks", List.of("value"), List.of(record), -1,
                new ProjectionGeneration(0), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> new ActiveArtifactSnapshot(
                "masks", List.of("value"), java.util.Arrays.asList(record, null), 0,
                new ProjectionGeneration(0), NOW))
                .isInstanceOf(NullPointerException.class);

        assertThat(new ArtifactProjectionConvergenceResult(
                1, 2, List.of("masks")).projectedArtifacts()).containsExactly("masks");
        assertThatThrownBy(() -> new ArtifactProjectionConvergenceResult(
                0, 0, List.of("masks")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
        assertThatThrownBy(() -> new ArtifactProjectionConvergenceResult(
                0, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
    }

    @Test
    void canonicalChangeEventRequiresACompleteNonEmptyArtifactSet() {
        CanonicalArtifactsChanged event = CanonicalArtifactsChanged.from(
                "operation-1", List.of("masks", "hashes"), NOW.value());

        assertThat(event.metadata().eventType()).isEqualTo(CanonicalArtifactsChanged.EVENT_TYPE);
        assertThat(event.metadata().correlationId()).isEqualTo("operation-1");
        assertThat(event.affectedArtifacts()).containsExactly("masks", "hashes");
        assertThatThrownBy(() -> CanonicalArtifactsChanged.from(" ", List.of("masks"), NOW.value()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationId");
        assertThatThrownBy(() -> CanonicalArtifactsChanged.from("operation", List.of(), NOW.value()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> CanonicalArtifactsChanged.from(
                "operation", java.util.Arrays.asList("masks", null), NOW.value()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactName");
        assertThatThrownBy(() -> CanonicalArtifactsChanged.from(
                "operation", List.of("masks"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("occurredAt");
    }

    @Test
    void confirmationAndReceiptSnapshotsRejectAmbiguousOrIncompleteRows() {
        ConfirmationReceiptContext context = new ConfirmationReceiptContext(
                new ConfirmationReceiptId("receipt-1"), "policy-v1", 1, Duration.ofDays(1));
        PreparedArtifactRow prepared = new PreparedArtifactRow(
                new ArtifactRow(Map.of("mask", "example.test")), Optional.empty());
        CanonicalRecordConfirmation record = new CanonicalRecordConfirmation(
                new ArtifactRowKey("row-1"), prepared);

        assertThat(new CanonicalArtifactConfirmation(
                new ObservationId("observation-1"), "source-1", context,
                "masks", List.of("mask"), List.of(record)).records()).containsExactly(record);
        assertThatThrownBy(() -> new CanonicalArtifactConfirmation(
                new ObservationId("observation-1"), "source-1", context,
                "masks", List.of(), List.of(record)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header");
        assertThatThrownBy(() -> new CanonicalArtifactConfirmation(
                new ObservationId("observation-1"), "source-1", context,
                "masks", List.of("mask"), List.of(record, record)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate row key");

        ConfirmationReceiptArtifact artifact = new ConfirmationReceiptArtifact(
                "masks", List.of("mask"), List.of(record));
        assertThat(new ConfirmationReceiptSnapshot(
                new ConfirmationReceiptId("receipt-1"), "source-1", "policy-v1",
                List.of(artifact)).artifacts()).containsExactly(artifact);
        assertThatThrownBy(() -> new ConfirmationReceiptArtifact(" ", List.of("mask"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity and header");
        assertThatThrownBy(() -> new ConfirmationReceiptArtifact("masks", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity and header");
        assertThatThrownBy(() -> new ConfirmationReceiptSnapshot(
                new ConfirmationReceiptId("receipt-1"), " ", "policy-v1", List.of(artifact)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank or empty");
        assertThatThrownBy(() -> new ConfirmationReceiptSnapshot(
                new ConfirmationReceiptId("receipt-1"), "source-1", " ", List.of(artifact)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank or empty");
        assertThatThrownBy(() -> new ConfirmationReceiptSnapshot(
                new ConfirmationReceiptId("receipt-1"), "source-1", "policy-v1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank or empty");
    }

    @Test
    void lifecycleStatusAggregatesCountsAndRejectsNegativeOperatorEvidence() {
        LifecycleClockSnapshot clock = new LifecycleClockSnapshot(
                LifecycleClockStatus.SAFE, NOW.value(), NOW, Optional.of(NOW),
                Duration.ZERO, Duration.ZERO);
        LifecycleStatusSnapshot status = new LifecycleStatusSnapshot(
                LifecycleControlState.disabledCompatible(), clock,
                List.of(
                        new LifecycleArtifactStatistics("masks", 3, 1, 2),
                        new LifecycleArtifactStatistics("hashes", 4, 2, 5)),
                Optional.empty(), Optional.empty(), 1,
                LifecycleReconcileCycleState.COMPLETED,
                Optional.of(NOW.value()), Optional.of(NOW.value()), 3,
                Optional.empty(), Duration.ZERO);
        assertThat(status.dueRecords()).isEqualTo(3);
        assertThat(status.historyRecords()).isEqualTo(7);

        for (long[] counters : List.of(
                new long[] {-1, 0, 0},
                new long[] {0, -1, 0},
                new long[] {0, 0, -1})) {
            assertThatThrownBy(() -> new LifecycleStatusSnapshot(
                    LifecycleControlState.disabledCompatible(), clock, List.of(),
                    Optional.empty(), Optional.empty(), counters[0],
                    LifecycleReconcileCycleState.NEVER_RUN,
                    Optional.empty(), Optional.empty(), counters[1],
                    Optional.empty(), Duration.ofSeconds(counters[2])))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("counters");
        }
    }

    @Test
    void clockAndHistoryPoliciesRequireBoundedPositiveValues() {
        assertThatThrownBy(() -> new LifecycleClockPolicy(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackwardSkew");
        assertThatThrownBy(() -> new LifecycleClockPolicy(
                Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxClampDuration");
        assertThatThrownBy(() -> new LifecycleClockSnapshot(
                LifecycleClockStatus.CLAMPED, NOW.value(), NOW, Optional.of(NOW),
                Duration.ofSeconds(-1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwardSkew");
        assertThatThrownBy(() -> new LifecycleClockSnapshot(
                LifecycleClockStatus.CLAMPED, NOW.value(), NOW, Optional.of(NOW),
                Duration.ZERO, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clampAge");

        assertThat(new LifecycleHistoryStore.HistoryPurgeResult(
                "masks", 2, true).purged()).isEqualTo(2);
        assertThatThrownBy(() -> new LifecycleHistoryStore.HistoryPurgeResult(" ", 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactName");
        assertThatThrownBy(() -> new LifecycleHistoryStore.HistoryPurgeResult("masks", -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purged");
    }
}
