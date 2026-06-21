package com.iocextractor.domain.classify;

import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in registry of {@link FeaturePredicate}s, keyed by the names used in
 * {@code ioc.classify.rules[].when}. New predicate = one entry here (OCP); the
 * decision of which to use stays in configuration.
 */
public final class FeaturePredicates {

    private FeaturePredicates() {
    }

    public static Map<String, FeaturePredicate> defaults() {
        Map<String, FeaturePredicate> registry = new LinkedHashMap<>();
        registry.put("has-query", IndicatorFeatures::hasQuery);
        registry.put("has-path", IndicatorFeatures::hasPath);
        registry.put("has-port", IndicatorFeatures::hasPort);
        registry.put("has-path-or-port", f -> f.hasPath() || f.hasPort());
        registry.put("is-ip", IndicatorFeatures::isIp);
        registry.put("is-registrable", f -> f.hostKind() == HostKind.REGISTRABLE);
        registry.put("is-subdomain", f -> f.hostKind() == HostKind.SUBDOMAIN);
        registry.put("is-onion", f -> f.hostKind() == HostKind.ONION);
        return registry;
    }
}
