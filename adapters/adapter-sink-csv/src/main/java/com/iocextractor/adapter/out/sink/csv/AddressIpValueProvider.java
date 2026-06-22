package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.feature.NetworkAddressClassifier;
import com.iocextractor.domain.model.Indicator;

import java.util.Objects;

/**
 * Provider {@code address.ip}: emits <em>bare</em> IPv4 addresses for the address
 * blacklist, leaving every other address (domains, URLs and IP-URLs) to
 * {@code address.url}.
 */
public final class AddressIpValueProvider implements ValueProvider {

    private final IndicatorFeatureExtractor featureExtractor;

    public AddressIpValueProvider(IndicatorFeatureExtractor featureExtractor) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
    }

    @Override
    public String provide(long id, Indicator indicator) {
        return NetworkAddressClassifier.isBareIp(indicator, featureExtractor.extract(indicator))
                ? indicator.value() : null;
    }
}
