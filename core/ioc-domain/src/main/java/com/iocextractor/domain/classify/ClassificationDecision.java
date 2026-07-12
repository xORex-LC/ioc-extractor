package com.iocextractor.domain.classify;

import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.MaskMatch;

import java.util.List;
import java.util.Objects;

/**
 * Materialized first-match-wins classification for one indicator.
 *
 * @param features structural features computed for the indicator
 * @param matchedRuleIndex zero-based matched rule index, or {@code -1} when no rule matched
 * @param matchedPredicates configured predicate names of the matched rule
 * @param match resulting mask codes
 */
public record ClassificationDecision(IndicatorFeatures features,
                                     int matchedRuleIndex,
                                     List<String> matchedPredicates,
                                     MaskMatch match) {

    public ClassificationDecision {
        if (matchedRuleIndex < -1) {
            throw new IllegalArgumentException("matchedRuleIndex must be -1 or non-negative");
        }
        Objects.requireNonNull(features, "features");
        matchedPredicates = List.copyOf(Objects.requireNonNull(matchedPredicates, "matchedPredicates"));
        Objects.requireNonNull(match, "match");
    }
}
