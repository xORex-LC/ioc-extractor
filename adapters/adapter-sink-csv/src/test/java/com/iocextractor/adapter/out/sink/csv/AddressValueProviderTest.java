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

    @Test
    void address_url_provider_emits_domain_host_addresses_only() {
        var provider = new AddressUrlValueProvider(featureExtractor);

        assertThat(provider.provide(1, indicator("example.com/path", IndicatorType.DOMAIN)))
                .isEqualTo("example.com/path");
        assertThat(provider.provide(1, indicator("https://1.2.3.4/payload.exe", IndicatorType.URL)))
                .isNull();
    }

    @Test
    void address_ip_provider_emits_ip_host_addresses_only() {
        var provider = new AddressIpValueProvider(featureExtractor);

        assertThat(provider.provide(1, indicator("example.com/path", IndicatorType.DOMAIN)))
                .isNull();
        assertThat(provider.provide(1, indicator("https://1.2.3.4/payload.exe", IndicatorType.URL)))
                .isEqualTo("https://1.2.3.4/payload.exe");
    }

    private Indicator indicator(String value, IndicatorType type) {
        return new Indicator(value, type, SourceContext.UNKNOWN);
    }
}
