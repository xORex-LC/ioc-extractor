package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.AddressIpValueProvider;
import com.iocextractor.adapter.out.sink.csv.AddressUrlValueProvider;
import com.iocextractor.adapter.out.sink.csv.IdValueProvider;
import com.iocextractor.adapter.out.sink.csv.IndicatorValueProvider;
import com.iocextractor.adapter.out.sink.csv.LowerHostTransform;
import com.iocextractor.adapter.out.sink.csv.LowercaseTransform;
import com.iocextractor.adapter.out.sink.csv.MatchHostValueProvider;
import com.iocextractor.adapter.out.sink.csv.MatchUrlValueProvider;
import com.iocextractor.adapter.out.sink.csv.SourceLabelValueProvider;
import com.iocextractor.adapter.out.sink.csv.StripPrefixTransform;
import com.iocextractor.adapter.out.sink.csv.Transform;
import com.iocextractor.adapter.out.sink.csv.UppercaseTransform;
import com.iocextractor.adapter.out.sink.csv.ValueProvider;
import com.iocextractor.domain.classify.FeaturePredicate;
import com.iocextractor.domain.classify.FeaturePredicates;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.feature.NetworkAddressClassifier;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Composition-root catalog for config-backed registries and their public keys.
 */
final class ConfigRegistryCatalog {

    static final String CONST_VALUE_PROVIDER = "const";

    private static final String PROVIDER_ID = "id";
    private static final String PROVIDER_VALUE = "value";
    private static final String PROVIDER_SOURCE_LABEL = "source.label";
    private static final String PROVIDER_MATCH_URL = "match.url";
    private static final String PROVIDER_MATCH_HOST = "match.host";
    private static final String PROVIDER_ADDRESS_URL = "address.url";
    private static final String PROVIDER_ADDRESS_IP = "address.ip";

    private static final String FILTER_IS_BARE_IP = "is-bare-ip";

    private static final String TRANSFORM_LOWER = "lower";
    private static final String TRANSFORM_LOWER_HOST = "lower-host";
    private static final String TRANSFORM_UPPER = "upper";
    private static final String TRANSFORM_STRIP_PREFIX = "strip-prefix";

    private ConfigRegistryCatalog() {
    }

    static Map<String, FeaturePredicate> featurePredicates() {
        return FeaturePredicates.defaults();
    }

    static Set<String> classifyPredicateKeys() {
        return featurePredicates().keySet();
    }

    static Set<String> artifactFilterKeys() {
        Set<String> keys = new LinkedHashSet<>(classifyPredicateKeys());
        keys.add(FILTER_IS_BARE_IP);
        return keys;
    }

    static Set<String> valueProviderKeys() {
        return Set.of(
                PROVIDER_ID,
                PROVIDER_VALUE,
                PROVIDER_SOURCE_LABEL,
                PROVIDER_MATCH_URL,
                PROVIDER_MATCH_HOST,
                PROVIDER_ADDRESS_URL,
                PROVIDER_ADDRESS_IP);
    }

    static Set<String> transformKeys() {
        return Set.of(
                TRANSFORM_LOWER,
                TRANSFORM_LOWER_HOST,
                TRANSFORM_UPPER,
                TRANSFORM_STRIP_PREFIX);
    }

    static Map<String, ValueProvider> valueProviders() {
        Map<String, ValueProvider> providers = new HashMap<>();
        providers.put(PROVIDER_ID, new IdValueProvider());
        providers.put(PROVIDER_VALUE, new IndicatorValueProvider());
        providers.put(PROVIDER_SOURCE_LABEL, new SourceLabelValueProvider());
        providers.put(PROVIDER_MATCH_URL, new MatchUrlValueProvider());
        providers.put(PROVIDER_MATCH_HOST, new MatchHostValueProvider());
        providers.put(PROVIDER_ADDRESS_URL, new AddressUrlValueProvider());
        providers.put(PROVIDER_ADDRESS_IP, new AddressIpValueProvider());
        return providers;
    }

    static Map<String, Predicate<ClassifiedIndicator>> artifactFilters() {
        Map<String, Predicate<ClassifiedIndicator>> filters = new HashMap<>();
        featurePredicates().forEach((key, predicate) ->
                filters.put(key, classified -> predicate.test(classified.classification().features())));
        filters.put(FILTER_IS_BARE_IP, classified -> NetworkAddressClassifier.isBareIp(
                classified.indicator(), classified.classification().features()));
        return filters;
    }

    static Map<String, Transform> transforms() {
        Map<String, Transform> transforms = new HashMap<>();
        transforms.put(TRANSFORM_LOWER, new LowercaseTransform());
        transforms.put(TRANSFORM_LOWER_HOST, new LowerHostTransform());
        transforms.put(TRANSFORM_UPPER, new UppercaseTransform());
        transforms.put(TRANSFORM_STRIP_PREFIX, new StripPrefixTransform());
        return transforms;
    }
}
