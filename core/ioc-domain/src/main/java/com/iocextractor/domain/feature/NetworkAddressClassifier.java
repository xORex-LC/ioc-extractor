package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;

/**
 * Single source of truth for the "bare IP" notion shared by mask exclusion,
 * IP-list inclusion and address-blacklist column routing.
 *
 * <p>A <em>bare IP</em> is a plain IPv4 literal with no port, path or query.
 * An IP-URL such as {@code http://1.2.3.4} (type {@code URL}) or
 * {@code 1.2.3.4:8080/payload.exe} (type {@code IPV4} with a port/path) is
 * <strong>not</strong> bare — it belongs with URL-shaped addresses.
 */
public final class NetworkAddressClassifier {

    private NetworkAddressClassifier() {
    }

    /**
     * Tests whether an indicator is a bare IPv4 literal.
     *
     * @param indicator indicator under test
     * @param features  its derived structural features
     * @return {@code true} only for an IPv4 host with no port, path or query
     */
    public static boolean isBareIp(Indicator indicator, IndicatorFeatures features) {
        return indicator.type() == IndicatorType.IPV4
                && features.isIp()
                && !features.hasPort()
                && !features.hasPath()
                && !features.hasQuery();
    }
}
