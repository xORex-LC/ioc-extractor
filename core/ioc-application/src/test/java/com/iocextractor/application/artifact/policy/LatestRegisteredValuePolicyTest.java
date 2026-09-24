package com.iocextractor.application.artifact.policy;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.OccurrencePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatestRegisteredValuePolicyTest {

    private final LatestRegisteredValuePolicy policy = new LatestRegisteredValuePolicy();

    @Test
    void later_same_value_advances_origin_so_older_completion_cannot_replace_it() {
        FieldValueOrigin first = origin(1, "first");
        FieldValueOrigin latest = origin(3, "latest");
        assertThat(policy.decide("A", first, "A", latest))
                .isEqualTo(FieldUpdateDecision.ADVANCE_ORIGIN_ONLY);
        assertThat(policy.decide("A", latest, "B", origin(2, "middle")))
                .isEqualTo(FieldUpdateDecision.PRESERVE);
    }

    @Test
    void blank_observation_does_not_advance_the_barrier() {
        FieldValueOrigin later = origin(3, "later");
        assertThat(policy.decide("A", origin(1, "first"), "  ", later))
                .isEqualTo(FieldUpdateDecision.PRESERVE);
        assertThat(policy.decide("A", origin(1, "first"), "B", origin(2, "middle")))
                .isEqualTo(FieldUpdateDecision.CHANGE_PUBLIC_VALUE);
    }

    @Test
    void contradictory_reuse_of_one_rank_is_rejected() {
        FieldValueOrigin existing = origin(4, "same");
        assertThatThrownBy(() -> policy.decide("A", existing, "B", existing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting evidence");
    }

    @Test
    void missingValuesAndEqualRanksPreserveDeterministicOrdering() {
        FieldValueOrigin existing = origin(4, "same");

        assertThat(policy.decide(null, null, "A", existing))
                .isEqualTo(FieldUpdateDecision.CHANGE_PUBLIC_VALUE);
        assertThat(policy.decide("A", null, "A", existing))
                .isEqualTo(FieldUpdateDecision.ADVANCE_ORIGIN_ONLY);
        assertThat(policy.decide("A", existing, null, origin(5, "later")))
                .isEqualTo(FieldUpdateDecision.PRESERVE);
        assertThat(policy.decide("A", existing, "A", existing))
                .isEqualTo(FieldUpdateDecision.PRESERVE);
        assertThatThrownBy(() -> policy.decide("A", existing, "A", origin(4, "other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting evidence");
    }

    @Test
    void last_nonempty_selection_keeps_one_whole_occurrence() {
        var selector = new ArtifactOccurrenceSelector();
        var selectionPolicy = new ArtifactWritePolicy(
                ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY,
                "name", Map.of());
        List<Candidate> candidates = List.of(
                new Candidate("first", "10.0.0.1"),
                new Candidate("latest", "10.0.0.2"),
                new Candidate(" ", "10.0.0.3"));

        assertThat(selector.select(candidates, selectionPolicy, Candidate::name))
                .isEqualTo(new Candidate("latest", "10.0.0.2"));
    }

    @Test
    void legacy_selection_keeps_first_occurrence() {
        var selector = new ArtifactOccurrenceSelector();
        List<Candidate> candidates = List.of(
                new Candidate(null, "10.0.0.1"),
                new Candidate("later", "10.0.0.2"));

        assertThat(selector.select(candidates, ArtifactWritePolicy.legacy(), Candidate::name))
                .isSameAs(candidates.getFirst());
    }

    @Test
    void policyAndSelectionRejectMissingRequirementsWithoutPartialSelection() {
        assertThatThrownBy(() -> new ArtifactWritePolicy(
                ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a column");
        assertThatThrownBy(() -> new ArtifactWritePolicy(
                ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY, " ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a column");

        var selector = new ArtifactOccurrenceSelector();
        var selectionPolicy = new ArtifactWritePolicy(
                ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY, "name", Map.of());
        assertThatThrownBy(() -> selector.select(List.<Candidate>of(), selectionPolicy, Candidate::name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty group");

        List<Candidate> unnamed = List.of(
                new Candidate(null, "10.0.0.1"),
                new Candidate(" ", "10.0.0.2"));
        assertThat(selector.select(unnamed, selectionPolicy, Candidate::name))
                .isSameAs(unnamed.getFirst());
    }

    private static FieldValueOrigin origin(long order, String id) {
        return new FieldValueOrigin(
                new ObservationOrder(order), new OccurrencePosition(0), new ObservationId(id));
    }

    private record Candidate(String name, String value) {
    }
}
