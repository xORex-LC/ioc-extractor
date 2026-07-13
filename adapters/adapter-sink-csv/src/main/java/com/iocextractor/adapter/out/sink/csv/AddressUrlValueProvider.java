package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.feature.NetworkAddressClassifier;

/**
 * Provider {@code address.url}: emits every non-bare-IP network address (domains,
 * URLs and IP-URLs such as {@code 1.2.3.4:8080/x}) for the address blacklist,
 * leaving bare IPv4 literals to {@code address.ip}.
 */
public final class AddressUrlValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator classified) {
        var indicator = classified.indicator();
        return NetworkAddressClassifier.isBareIp(indicator, classified.classification().features())
                ? null : indicator.value();
    }
}
