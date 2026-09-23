package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.model.Indicator;

import java.util.List;
import java.util.Objects;

/**
 * Attributed indicators retained after optional within-batch de-duplication.
 *
 * @param extracted number of indicators before de-duplication
 * @param retained indicators retained for downstream processing
 * @param decisions keep/drop decision for every input indicator
 */
public record DeduplicatedIndicators(int extracted,
                                     List<Indicator> retained,
                                     List<IndicatorOccurrence> occurrences,
                                     List<DeduplicationDecision> decisions) {

    public DeduplicatedIndicators {
        if (extracted < 0) {
            throw new IllegalArgumentException("extracted must be non-negative");
        }
        retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
        occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        if (retained.size() > extracted) {
            throw new IllegalArgumentException("retained size must not exceed extracted count");
        }
        if (decisions.size() != extracted) {
            throw new IllegalArgumentException("decisions size must equal extracted count");
        }
        if (occurrences.size() != extracted) {
            throw new IllegalArgumentException("occurrences size must equal extracted count");
        }
    }

    /** Compatibility constructor for callers that provide the full decision stream. */
    public DeduplicatedIndicators(int extracted,
                                  List<Indicator> retained,
                                  List<DeduplicationDecision> decisions) {
        this(extracted, retained, java.util.stream.IntStream.range(0, decisions.size())
                .mapToObj(index -> new IndicatorOccurrence(
                        decisions.get(index).indicator(), index, index))
                .toList(), decisions);
    }
}
