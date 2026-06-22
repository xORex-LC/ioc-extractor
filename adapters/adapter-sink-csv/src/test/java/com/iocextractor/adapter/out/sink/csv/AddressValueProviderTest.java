package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.feature.DefaultIndicatorFeatureExtractor;
import com.iocextractor.domain.feature.DefaultIndicatorNormalizer;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AddressValueProviderTest {

    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private final DefaultIndicatorFeatureExtractor featureExtractor = new DefaultIndicatorFeatureExtractor(
            new DefaultIndicatorNormalizer(),
            host -> IPV4.matcher(host).matches() ? HostKind.IP : HostKind.REGISTRABLE);

    private final AddressUrlValueProvider url = new AddressUrlValueProvider(featureExtractor);
    private final AddressIpValueProvider ip = new AddressIpValueProvider(featureExtractor);

    @Test
    void bare_ip_goes_to_ip_column_only() {
        var bareIp = indicator("1.2.3.4", IndicatorType.IPV4);

        assertThat(ip.provide(1, bareIp)).isEqualTo("1.2.3.4");
        assertThat(url.provide(1, bareIp)).isNull();
    }

    @Test
    void ip_url_stays_in_url_column() {
        // IP-URL: an IPv4 host carrying a port/path is NOT bare -> forbidden_url.
        var ipWithPort = indicator("5.6.7.8:8080/Payload.exe", IndicatorType.IPV4);
        var schemeIpUrl = indicator("https://1.2.3.4/payload.exe", IndicatorType.URL);

        assertThat(url.provide(1, ipWithPort)).isEqualTo("5.6.7.8:8080/Payload.exe");
        assertThat(ip.provide(1, ipWithPort)).isNull();
        assertThat(url.provide(1, schemeIpUrl)).isEqualTo("https://1.2.3.4/payload.exe");
        assertThat(ip.provide(1, schemeIpUrl)).isNull();
    }

    @Test
    void domain_addresses_go_to_url_column_only() {
        var domain = indicator("example.com/path", IndicatorType.DOMAIN);

        assertThat(url.provide(1, domain)).isEqualTo("example.com/path");
        assertThat(ip.provide(1, domain)).isNull();
    }

    private Indicator indicator(String value, IndicatorType type) {
        return new Indicator(value, type, SourceContext.UNKNOWN);
    }
}
