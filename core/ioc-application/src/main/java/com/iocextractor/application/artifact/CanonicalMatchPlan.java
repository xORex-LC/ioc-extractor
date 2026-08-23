package com.iocextractor.application.artifact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Zero/one/multi active-match decision for one logical record. */
public record CanonicalMatchPlan(String requestId,
                                 CanonicalMatchCardinality cardinality,
                                 List<CanonicalMatchCandidate> candidates) {

    public CanonicalMatchPlan {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Canonical match request id must not be blank");
        }
        Objects.requireNonNull(cardinality, "cardinality");
        candidates = deduplicate(candidates);
        CanonicalMatchCardinality actual = candidates.isEmpty()
                ? CanonicalMatchCardinality.ZERO
                : candidates.size() == 1 ? CanonicalMatchCardinality.ONE : CanonicalMatchCardinality.MULTIPLE;
        if (cardinality != actual) {
            throw new IllegalArgumentException("Canonical match cardinality does not match candidates");
        }
    }

    /** Creates a cardinality-safe plan from possibly repeated alias hits. */
    public static CanonicalMatchPlan from(String requestId, List<CanonicalMatchCandidate> candidates) {
        List<CanonicalMatchCandidate> unique = deduplicate(candidates);
        CanonicalMatchCardinality cardinality = unique.isEmpty()
                ? CanonicalMatchCardinality.ZERO
                : unique.size() == 1 ? CanonicalMatchCardinality.ONE : CanonicalMatchCardinality.MULTIPLE;
        return new CanonicalMatchPlan(requestId, cardinality, unique);
    }

    /** Returns the candidate only when the plan is unambiguous. */
    public Optional<CanonicalMatchCandidate> exactCandidate() {
        return cardinality == CanonicalMatchCardinality.ONE
                ? Optional.of(candidates.getFirst())
                : Optional.empty();
    }

    private static List<CanonicalMatchCandidate> deduplicate(List<CanonicalMatchCandidate> values) {
        Objects.requireNonNull(values, "candidates");
        var unique = new LinkedHashMap<String, CanonicalMatchCandidate>();
        for (CanonicalMatchCandidate candidate : values) {
            Objects.requireNonNull(candidate, "candidates element");
            unique.putIfAbsent(candidate.canonicalRowId() + ":" + candidate.lifecycleId(), candidate);
        }
        return List.copyOf(unique.values());
    }
}
