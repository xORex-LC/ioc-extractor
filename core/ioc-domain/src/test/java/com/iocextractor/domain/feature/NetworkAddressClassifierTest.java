package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkAddressClassifierTest {

    @Test
    void bare_ipv4_is_bare() {
        assertThat(NetworkAddressClassifier.isBareIp(
                indicator("1.2.3.4", IndicatorType.IPV4),
                features("1.2.3.4", false, false, false, HostKind.IP))).isTrue();
    }

    @Test
    void ipv4_with_port_or_path_is_not_bare() {
        assertThat(NetworkAddressClassifier.isBareIp(
                indicator("1.2.3.4:8080", IndicatorType.IPV4),
                features("1.2.3.4", true, false, false, HostKind.IP))).isFalse();
        assertThat(NetworkAddressClassifier.isBareIp(
                indicator("1.2.3.4/x", IndicatorType.IPV4),
                features("1.2.3.4", false, true, false, HostKind.IP))).isFalse();
    }

    @Test
    void scheme_ip_url_is_not_bare() {
        // http://1.2.3.4 is extracted as a URL, not as an IPV4 indicator.
        assertThat(NetworkAddressClassifier.isBareIp(
                indicator("http://1.2.3.4", IndicatorType.URL),
                features("1.2.3.4", false, false, false, HostKind.IP))).isFalse();
    }

    @Test
    void domain_is_not_bare() {
        assertThat(NetworkAddressClassifier.isBareIp(
                indicator("example.com", IndicatorType.DOMAIN),
                features("example.com", false, false, false, HostKind.REGISTRABLE))).isFalse();
    }

    private Indicator indicator(String value, IndicatorType type) {
        return new Indicator(value, type, new SourceContext(null, null));
    }

    private IndicatorFeatures features(String host, boolean port, boolean path, boolean query, HostKind kind) {
        return new IndicatorFeatures(host, host, port, path, query, kind);
    }
}
