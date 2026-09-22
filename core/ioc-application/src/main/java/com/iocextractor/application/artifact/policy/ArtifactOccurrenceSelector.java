package com.iocextractor.application.artifact.policy;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Chooses one whole candidate; later blank labels cannot erase an earlier named occurrence. */
public final class ArtifactOccurrenceSelector {

    public <T> T select(List<T> candidates, ArtifactWritePolicy policy,
                        Function<T, String> selectedField) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(selectedField, "selectedField");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Cannot select an occurrence from an empty group");
        }
        if (policy.duplicateSelection() == ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST) {
            return candidates.getFirst();
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            T candidate = candidates.get(i);
            String value = selectedField.apply(candidate);
            if (value != null && !value.isBlank()) {
                return candidate;
            }
        }
        return candidates.getFirst();
    }
}
