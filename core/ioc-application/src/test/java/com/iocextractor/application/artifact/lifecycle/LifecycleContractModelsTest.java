package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
}
