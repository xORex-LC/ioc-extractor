package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.model.Indicator;

import java.util.Objects;

/**
 * Provider {@code address.ip}: emits addresses whose host is an IP literal for
 * the address blacklist, leaving domain-host addresses to {@code address.url}.
 */
public final class AddressIpValueProvider implements ValueProvider {

    private final IndicatorFeatureExtractor featureExtractor;

    public AddressIpValueProvider(IndicatorFeatureExtractor featureExtractor) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
    }

    @Override
    public String provide(long id, Indicator indicator) {
        return featureExtractor.extract(indicator).isIp() ? indicator.value() : null;
    }
}
