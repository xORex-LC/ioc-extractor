package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;

/**
 * Default {@link IndicatorFeatureExtractor}. The shared address parser owns
 * authority boundaries and supported forms; the host kind is delegated to the
 * {@link HostClassifier} port. An invalid address has unknown host features.
 */
public final class DefaultIndicatorFeatureExtractor implements IndicatorFeatureExtractor {

    private final IndicatorNormalizer normalizer;
    private final HostClassifier hostClassifier;
    private final NetworkAddressParser parser = new NetworkAddressParser();

    public DefaultIndicatorFeatureExtractor(IndicatorNormalizer normalizer, HostClassifier hostClassifier) {
        this.normalizer = normalizer;
        this.hostClassifier = hostClassifier;
    }

    @Override
    public IndicatorFeatures extract(Indicator indicator) {
        String value = normalizer.normalize(indicator.value());
        NetworkAddressParser.Result result = parser.parse(value);
        if (!result.isAvailable()) {
            return new IndicatorFeatures(value, value, false, false, false, HostKind.UNKNOWN);
        }
        NetworkAddressParser.Address address = result.address();
        HostKind kind = hostClassifier.classify(address.host());
        return new IndicatorFeatures(value, address.host(), address.hasPort(),
                address.hasPath(), address.hasQuery(), kind);
    }
}
