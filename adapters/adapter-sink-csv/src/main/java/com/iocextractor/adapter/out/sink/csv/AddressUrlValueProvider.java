package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.model.Indicator;

import java.util.Objects;

/**
 * Provider {@code address.url}: emits non-IP-host network addresses for the
 * address blacklist, leaving IP-host addresses to {@code address.ip}.
 */
public final class AddressUrlValueProvider implements ValueProvider {

    private final IndicatorFeatureExtractor featureExtractor;

    public AddressUrlValueProvider(IndicatorFeatureExtractor featureExtractor) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
    }

    @Override
    public String provide(long id, Indicator indicator) {
        return featureExtractor.extract(indicator).isIp() ? null : indicator.value();
    }
}
