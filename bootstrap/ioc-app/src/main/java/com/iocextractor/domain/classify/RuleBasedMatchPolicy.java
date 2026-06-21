package com.iocextractor.domain.classify;

import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.MaskMatch;

import java.util.List;

/**
 * Declarative {@link MatchPolicy}: derives {@link IndicatorFeatures} once, then
 * returns the codes of the first rule whose predicates all hold (first-match-wins).
 * The rules and codes are configuration; this class is the thin evaluator.
 */
public final class RuleBasedMatchPolicy implements MatchPolicy {

    private final IndicatorFeatureExtractor featureExtractor;
    private final List<MatchRule> rules;

    public RuleBasedMatchPolicy(IndicatorFeatureExtractor featureExtractor, List<MatchRule> rules) {
        this.featureExtractor = featureExtractor;
        this.rules = List.copyOf(rules);
    }

    @Override
    public MaskMatch classify(Indicator indicator) {
        IndicatorFeatures features = featureExtractor.extract(indicator);
        for (MatchRule rule : rules) {
            if (rule.matches(features)) {
                return rule.codes();
            }
        }
        return new MaskMatch(null, null); // unreachable when a catch-all default rule is present
    }
}
