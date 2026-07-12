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
    public ClassificationDecision classify(Indicator indicator) {
        IndicatorFeatures features = featureExtractor.extract(indicator);
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            MatchRule rule = rules.get(ruleIndex);
            if (rule.matches(features)) {
                return new ClassificationDecision(
                        features, ruleIndex, rule.predicateNames(), rule.codes());
            }
        }
        return new ClassificationDecision(
                features, -1, List.of(), new MaskMatch(null, null));
    }
}
