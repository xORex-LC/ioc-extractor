package com.iocextractor.domain.classify;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMatchPolicyTest {

    private final MaskMatch bareHost = new MaskMatch("u:hAS", "h:dAS");
    private final MaskMatch fullUrl = new MaskMatch("u:hEX,dEX", null);
    private final MatchPolicy policy = new DefaultMatchPolicy(bareHost, fullUrl);

    private Indicator indicator(String value, IndicatorType type) {
        return new Indicator(value, type, SourceContext.UNKNOWN);
    }

    @Test
    void bare_domain_is_classified_as_host_mask() {
        assertThat(policy.classify(indicator("zeccecard.com", IndicatorType.DOMAIN))).isEqualTo(bareHost);
    }

    @Test
    void bare_ip_is_classified_as_host_mask() {
        assertThat(policy.classify(indicator("159.198.41.140", IndicatorType.IPV4))).isEqualTo(bareHost);
    }

    @Test
    void domain_with_path_is_full_url() {
        assertThat(policy.classify(indicator("zeccecard.com/grain/duke", IndicatorType.DOMAIN))).isEqualTo(fullUrl);
    }

    @Test
    void ip_with_port_is_full_url() {
        assertThat(policy.classify(indicator("185.238.189.41:8080", IndicatorType.IPV4))).isEqualTo(fullUrl);
    }

    @Test
    void url_is_always_full_url() {
        assertThat(policy.classify(indicator("https://x.y/z", IndicatorType.URL))).isEqualTo(fullUrl);
    }
}
