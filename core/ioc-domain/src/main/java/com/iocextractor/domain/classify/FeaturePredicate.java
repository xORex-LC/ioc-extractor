package com.iocextractor.domain.classify;

import com.iocextractor.domain.feature.IndicatorFeatures;

/**
 * A thin, named test over {@link IndicatorFeatures}, used by declarative match
 * rules. Predicates are the reusable "instruments"; which ones a rule uses is
 * configuration ({@code ioc.classify.rules[].when}).
 */
@FunctionalInterface
public interface FeaturePredicate {

    boolean test(IndicatorFeatures features);
}
