package com.iocextractor.application.artifact.policy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactOccurrenceSelectorTest {
    private final ArtifactOccurrenceSelector selector = new ArtifactOccurrenceSelector();
    private final ArtifactWritePolicy last = new ArtifactWritePolicy(
            ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY, "label", Map.of());

    @Test
    void incrementalReplacementPreservesFirstKeyOrderAndWholeWinner() {
        var accumulator = selector.<String, Candidate>accumulator(last, Candidate::label);
        var first = new Candidate("first", 1);
        var replacement = new Candidate("last", 4);
        accumulator.add("a", first);
        accumulator.add("b", new Candidate(null, 2));
        accumulator.add("a", replacement);
        accumulator.add("a", new Candidate(" ", 5));
        accumulator.add("b", new Candidate("", 6));
        assertThat(accumulator.winners()).containsExactly(replacement, new Candidate(null, 2));
        assertThatThrownBy(() -> accumulator.winners().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listAndIncrementalSelectionAgreeForBlankGroupsAndTies() {
        for (List<Candidate> candidates : List.of(
                Arrays.asList(new Candidate(null, 1), new Candidate(" ", 2)),
                List.of(new Candidate("same", 1), new Candidate("same", 2)),
                List.of(new Candidate("named", 1), new Candidate("", 2)))) {
            var accumulator = selector.<String, Candidate>accumulator(last, Candidate::label);
            candidates.forEach(candidate -> accumulator.add("key", candidate));
            assertThat(selector.select(candidates, last, Candidate::label))
                    .isEqualTo(accumulator.winners().getFirst());
        }
    }

    @Test
    void keepFirstDoesNotEvaluateSelectionField() {
        var accumulator = selector.<String, String>accumulator(ArtifactWritePolicy.legacy(), ignored -> {
            throw new AssertionError("KEEP_FIRST has no selection field");
        });
        accumulator.add("key", "first");
        accumulator.add("key", "last");
        assertThat(accumulator.winners()).containsExactly("first");
        assertThatThrownBy(() -> selector.select(List.of(), last, Object::toString))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record Candidate(String label, int position) { }
}
