package com.iocextractor.domain.classify;

import com.iocextractor.adapter.out.psl.PslHostClassifier;
import com.iocextractor.domain.feature.DefaultIndicatorFeatureExtractor;
import com.iocextractor.domain.feature.DefaultIndicatorNormalizer;
import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the whole classification chain (feature extraction + PSL host
 * classifier + the default 4-variant rules) against the buckets in
 * docs/extraction.md. Synthetic inputs only — no project artifacts.
 */
class RuleBasedMatchPolicyTest {

    private final MatchPolicy policy = defaultPolicy();

    private static MatchPolicy defaultPolicy() {
        Map<String, FeaturePredicate> reg = FeaturePredicates.defaults();
        IndicatorFeatureExtractor extractor =
                new DefaultIndicatorFeatureExtractor(new DefaultIndicatorNormalizer(), new PslHostClassifier());
        List<MatchRule> rules = List.of(
                new MatchRule(List.of(reg.get("has-query")), new MaskMatch("u:hAS,pEX", null)),
                new MatchRule(List.of(reg.get("has-path-or-port")), new MaskMatch("u:hEX,dEX", null)),
                new MatchRule(List.of(reg.get("is-subdomain")), new MaskMatch("u:hEX", "h:dEX")),
                new MatchRule(List.of(), new MaskMatch("u:hAS", "h:dAS")));
        return new RuleBasedMatchPolicy(extractor, rules);
    }

    private MaskMatch classify(String value, IndicatorType type) {
        return policy.classify(new Indicator(value, type, SourceContext.UNKNOWN));
    }

    @Test
    void registrable_domain_is_variant1() {
        assertThat(classify("zeccecard.com", IndicatorType.DOMAIN)).isEqualTo(new MaskMatch("u:hAS", "h:dAS"));
    }

    @Test
    void subdomain_is_variant2() {
        assertThat(classify("why.yutikeyu.com", IndicatorType.DOMAIN)).isEqualTo(new MaskMatch("u:hEX", "h:dEX"));
    }

    @Test
    void bare_ip_is_variant1() {
        assertThat(classify("159.198.41.140", IndicatorType.IPV4)).isEqualTo(new MaskMatch("u:hAS", "h:dAS"));
    }

    @Test
    void host_with_path_is_variant3() {
        assertThat(classify("zeccecard.com/grain/duke", IndicatorType.DOMAIN)).isEqualTo(new MaskMatch("u:hEX,dEX", null));
    }

    @Test
    void ip_with_port_is_variant3() {
        assertThat(classify("185.238.189.41:8080", IndicatorType.IPV4)).isEqualTo(new MaskMatch("u:hEX,dEX", null));
    }

    @Test
    void url_with_query_is_variant4() {
        assertThat(classify("zeccecard.com/x?asdzq", IndicatorType.DOMAIN)).isEqualTo(new MaskMatch("u:hAS,pEX", null));
    }

    @Test
    void onion_is_variant1() {
        assertThat(classify("pacrhxuvp7jkk6lrpo3qcdfus2y2jsn25cpbsy3mqncnhbs6gpzi5aad.onion", IndicatorType.DOMAIN))
                .isEqualTo(new MaskMatch("u:hAS", "h:dAS"));
    }
}
