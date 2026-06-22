package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.feature.NetworkAddressClassifier;
import com.iocextractor.domain.model.Indicator;

import java.util.Objects;

/**
 * Provider {@code address.url}: emits every non-bare-IP network address (domains,
 * URLs and IP-URLs such as {@code 1.2.3.4:8080/x}) for the address blacklist,
 * leaving bare IPv4 literals to {@code address.ip}.
 */
public final class AddressUrlValueProvider implements ValueProvider {

    private final IndicatorFeatureExtractor featureExtractor;

    public AddressUrlValueProvider(IndicatorFeatureExtractor featureExtractor) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
    }

    @Override
    public String provide(long id, Indicator indicator) {
        return NetworkAddressClassifier.isBareIp(indicator, featureExtractor.extract(indicator))
                ? null : indicator.value();
    }
}
