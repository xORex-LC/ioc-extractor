package com.iocextractor.domain.classify;

import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.MaskMatch;

import java.util.List;

/**
 * One classification rule: the {@code when} predicates (combined with AND) and
 * the {@link MaskMatch} codes to emit when they all hold. An empty {@code when}
 * always matches — used as the catch-all default rule (variant 1).
 */
public record MatchRule(List<FeaturePredicate> when, MaskMatch codes) {

    public MatchRule {
        when = List.copyOf(when);
    }

    public boolean matches(IndicatorFeatures features) {
        for (FeaturePredicate predicate : when) {
            if (!predicate.test(features)) {
                return false;
            }
        }
        return true;
    }
}
