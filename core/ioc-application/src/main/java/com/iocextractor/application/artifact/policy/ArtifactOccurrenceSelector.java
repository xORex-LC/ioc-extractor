package com.iocextractor.application.artifact.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Chooses whole candidates without merging their fields or occurrence metadata. */
public final class ArtifactOccurrenceSelector {

    public <T> T select(List<T> candidates, ArtifactWritePolicy policy,
                        Function<T, String> selectedField) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(selectedField, "selectedField");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Cannot select an occurrence from an empty group");
        }
        T winner = candidates.getFirst();
        for (int index = 1; index < candidates.size(); index++) {
            winner = selectNext(winner, candidates.get(index), policy, selectedField);
        }
        return winner;
    }

    /** Creates a thread-confined reducer for one preparation invocation. */
    public <K, T> Accumulator<K, T> accumulator(ArtifactWritePolicy policy,
                                               Function<T, String> selectedField) {
        return new Accumulator<>(policy, selectedField);
    }

    private static <T> T selectNext(T winner, T candidate, ArtifactWritePolicy policy,
                                     Function<T, String> selectedField) {
        if (policy.duplicateSelection() == ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY) {
            String value = selectedField.apply(candidate);
            if (value != null && !value.isBlank()) {
                return candidate;
            }
        }
        return winner;
    }

    /** Retains one complete winner per final key, in first key encounter order. */
    public static final class Accumulator<K, T> {
        private final ArtifactWritePolicy policy;
        private final Function<T, String> selectedField;
        private final Map<K, T> winners = new LinkedHashMap<>();

        private Accumulator(ArtifactWritePolicy policy, Function<T, String> selectedField) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.selectedField = Objects.requireNonNull(selectedField, "selectedField");
        }

        /** Every candidate is visited; replacement never moves its key's output position. */
        public void add(K key, T candidate) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(candidate, "candidate");
            T current = winners.get(key);
            T winner = current == null ? candidate : selectNext(current, candidate, policy, selectedField);
            if (winner != current) {
                winners.put(key, winner);
            }
        }

        /** An immutable snapshot; losing candidates are no longer retained. */
        public List<T> winners() {
            return List.copyOf(winners.values());
        }
    }
}
