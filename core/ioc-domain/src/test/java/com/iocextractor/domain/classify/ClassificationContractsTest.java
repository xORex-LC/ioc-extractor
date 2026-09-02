package com.iocextractor.domain.classify;

import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ClassificationContractsTest {

    private static final MaskMatch MATCH = new MaskMatch("u:hAS", "h:dAS");

    @Test
    void exposes_the_complete_configured_predicate_vocabulary() {
        assertThat(FeaturePredicates.defaults())
                .containsOnlyKeys(
                        "has-query",
                        "has-path",
                        "has-port",
                        "has-path-or-port",
                        "is-ip",
                        "is-registrable",
                        "is-subdomain",
                        "is-onion");
    }

    @ParameterizedTest(name = "{0} recognizes {1}")
    @MethodSource("hostKindPredicates")
    void host_kind_predicates_match_only_their_declared_kind(
            String predicateName,
            HostKind matchingKind,
            HostKind differentKind) {
        FeaturePredicate predicate = FeaturePredicates.defaults().get(predicateName);

        assertThat(predicate.test(features(matchingKind, false, false, false))).isTrue();
        assertThat(predicate.test(features(differentKind, false, false, false))).isFalse();
    }

    @ParameterizedTest(name = "path={0}, port={1} -> {2}")
    @MethodSource("pathOrPortCases")
    void path_or_port_predicate_implements_boolean_union(
            boolean hasPath,
            boolean hasPort,
            boolean expected) {
        FeaturePredicate predicate = FeaturePredicates.defaults().get("has-path-or-port");

        assertThat(predicate.test(features(HostKind.REGISTRABLE, hasPort, hasPath, false)))
                .isEqualTo(expected);
    }

    @Test
    void match_rule_requires_predicate_names_to_align_with_predicates() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MatchRule(List.of("has-query"), List.of(), MATCH))
                .withMessage("predicateNames must align with predicates");
    }

    @Test
    void match_rule_requires_every_predicate_to_accept_features() {
        MatchRule rule = new MatchRule(
                List.of("first", "second"),
                List.of(ignored -> true, ignored -> false),
                MATCH);

        assertThat(rule.matches(features(HostKind.REGISTRABLE, false, false, false))).isFalse();
        assertThat(new MatchRule(List.of(), List.of(), MATCH)
                .matches(features(HostKind.REGISTRABLE, false, false, false))).isTrue();
    }

    @Test
    void policy_returns_an_explicit_unmatched_decision_when_no_rule_accepts() {
        IndicatorFeatures features = features(HostKind.REGISTRABLE, false, false, false);
        RuleBasedMatchPolicy policy = new RuleBasedMatchPolicy(
                ignored -> features,
                List.of(new MatchRule(List.of("never"), List.of(ignored -> false), MATCH)));

        ClassificationDecision decision = policy.classify(indicator());

        assertThat(decision.matchedRuleIndex()).isEqualTo(-1);
        assertThat(decision.matchedPredicates()).isEmpty();
        assertThat(decision.match()).isEqualTo(new MaskMatch(null, null));
        assertThat(decision.features()).isSameAs(features);
    }

    @Test
    void policy_stops_at_the_first_matching_rule() {
        IndicatorFeatures features = features(HostKind.REGISTRABLE, false, false, false);
        MaskMatch firstMatch = new MaskMatch("first", null);
        RuleBasedMatchPolicy policy = new RuleBasedMatchPolicy(
                ignored -> features,
                List.of(
                        new MatchRule(List.of(), List.of(), firstMatch),
                        new MatchRule(List.of(), List.of(), new MaskMatch("second", null))));

        ClassificationDecision decision = policy.classify(indicator());

        assertThat(decision.matchedRuleIndex()).isZero();
        assertThat(decision.match()).isEqualTo(firstMatch);
    }

    @Test
    void classification_decision_rejects_indexes_below_unmatched_sentinel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClassificationDecision(
                        features(HostKind.REGISTRABLE, false, false, false),
                        -2,
                        List.of(),
                        MATCH))
                .withMessage("matchedRuleIndex must be -1 or non-negative");
    }

    private static Stream<Arguments> hostKindPredicates() {
        return Stream.of(
                Arguments.of("is-registrable", HostKind.REGISTRABLE, HostKind.IP),
                Arguments.of("is-subdomain", HostKind.SUBDOMAIN, HostKind.REGISTRABLE),
                Arguments.of("is-onion", HostKind.ONION, HostKind.SUBDOMAIN));
    }

    private static Stream<Arguments> pathOrPortCases() {
        return Stream.of(
                Arguments.of(false, false, false),
                Arguments.of(true, false, true),
                Arguments.of(false, true, true),
                Arguments.of(true, true, true));
    }

    private static IndicatorFeatures features(
            HostKind hostKind,
            boolean hasPort,
            boolean hasPath,
            boolean hasQuery) {
        return new IndicatorFeatures(
                "example.com", "example.com", hasPort, hasPath, hasQuery, hostKind);
    }

    private static Indicator indicator() {
        return new Indicator(
                "example.com",
                IndicatorType.DOMAIN,
                new SourceContext(null, null));
    }
}
