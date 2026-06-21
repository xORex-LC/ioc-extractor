package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;

/**
 * Default {@link IndicatorFeatureExtractor}. Pure string parsing (no regex
 * engine, no PSL library): the host kind is delegated to the {@link HostClassifier}
 * port. A {@code :} counts as a port only inside the authority (before the first
 * {@code /} or {@code ?}), so a path token like {@code …/bot<id>:<token>} is not
 * mistaken for a port.
 */
public final class DefaultIndicatorFeatureExtractor implements IndicatorFeatureExtractor {

    private final IndicatorNormalizer normalizer;
    private final HostClassifier hostClassifier;

    public DefaultIndicatorFeatureExtractor(IndicatorNormalizer normalizer, HostClassifier hostClassifier) {
        this.normalizer = normalizer;
        this.hostClassifier = hostClassifier;
    }

    @Override
    public IndicatorFeatures extract(Indicator indicator) {
        String value = normalizer.normalize(indicator.value());
        String rest = stripScheme(value);

        int slash = rest.indexOf('/');
        int query = rest.indexOf('?');
        boolean hasPath = slash >= 0;
        boolean hasQuery = query >= 0;

        int authorityEnd = rest.length();
        if (slash >= 0) {
            authorityEnd = Math.min(authorityEnd, slash);
        }
        if (query >= 0) {
            authorityEnd = Math.min(authorityEnd, query);
        }
        String authority = rest.substring(0, authorityEnd);

        String host = authority;
        boolean hasPort = false;
        int colon = authority.indexOf(':');
        if (colon >= 0 && isAllDigits(authority.substring(colon + 1))) {
            host = authority.substring(0, colon);
            hasPort = true;
        }

        HostKind kind = hostClassifier.classify(host);
        return new IndicatorFeatures(value, host, hasPort, hasPath, hasQuery, kind);
    }

    private String stripScheme(String value) {
        int idx = value.indexOf("://");
        return idx >= 0 ? value.substring(idx + 3) : value;
    }

    private boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
