package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.feature.DefaultIndicatorFeatureExtractor;
import com.iocextractor.domain.feature.DefaultIndicatorNormalizer;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.model.MaskMatch;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AddressValueProviderTest {

    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private final DefaultIndicatorFeatureExtractor featureExtractor = new DefaultIndicatorFeatureExtractor(
            new DefaultIndicatorNormalizer(),
            host -> IPV4.matcher(host).matches() ? HostKind.IP : HostKind.REGISTRABLE);

    private final AddressUrlValueProvider url = new AddressUrlValueProvider();
    private final AddressIpValueProvider ip = new AddressIpValueProvider();

    @Test
    void bare_ip_goes_to_ip_column_only() {
        var bareIp = indicator("1.2.3.4", IndicatorType.IPV4);

        assertThat(ip.provide(bareIp)).isEqualTo("1.2.3.4");
        assertThat(url.provide(bareIp)).isNull();
    }

    @Test
    void ip_url_stays_in_url_column() {
        // IP-URL: an IPv4 host carrying a port/path is NOT bare -> forbidden_url.
        var ipWithPort = indicator("5.6.7.8:8080/Payload.exe", IndicatorType.IPV4);
        var schemeIpUrl = indicator("https://1.2.3.4/payload.exe", IndicatorType.URL);

        assertThat(url.provide(ipWithPort)).isEqualTo("5.6.7.8:8080/Payload.exe");
        assertThat(ip.provide(ipWithPort)).isNull();
        assertThat(url.provide(schemeIpUrl)).isEqualTo("https://1.2.3.4/payload.exe");
        assertThat(ip.provide(schemeIpUrl)).isNull();
    }

    @Test
    void domain_addresses_go_to_url_column_only() {
        var domain = indicator("example.com/path", IndicatorType.DOMAIN);

        assertThat(url.provide(domain)).isEqualTo("example.com/path");
        assertThat(ip.provide(domain)).isNull();
    }

    private ClassifiedIndicator indicator(String value, IndicatorType type) {
        var indicator = new Indicator(value, type, new SourceContext(null, null));
        return new ClassifiedIndicator(indicator, new ClassificationDecision(
                featureExtractor.extract(indicator), 0, java.util.List.of(), new MaskMatch(null, null)));
    }
}
