package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.feature.NetworkAddressClassifier;

/**
 * Provider {@code address.ip}: emits <em>bare</em> IPv4 addresses for the address
 * blacklist, leaving every other address (domains, URLs and IP-URLs) to
 * {@code address.url}.
 */
public final class AddressIpValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator classified) {
        var indicator = classified.indicator();
        return NetworkAddressClassifier.isBareIp(indicator, classified.classification().features())
                ? indicator.value() : null;
    }
}
