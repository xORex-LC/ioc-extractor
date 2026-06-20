package com.iocextractor.domain.classify;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.MaskMatch;

/**
 * Encodes the convention observed in the manual reference CSV:
 *
 * <pre>
 *   bare host / domain / IP (no path, no port) -> bareHost  (e.g. u:hAS / h:dAS)
 *   full URL  (has path and/or port)           -> fullUrl   (e.g. u:hEX,dEX / NULL)
 * </pre>
 *
 * The two {@link MaskMatch} templates are injected, so tuning the codes is a
 * configuration change, not a code change.
 */
public final class DefaultMatchPolicy implements MatchPolicy {

    private final MaskMatch bareHost;
    private final MaskMatch fullUrl;

    public DefaultMatchPolicy(MaskMatch bareHost, MaskMatch fullUrl) {
        this.bareHost = bareHost;
        this.fullUrl = fullUrl;
    }

    @Override
    public MaskMatch classify(Indicator indicator) {
        return isFullUrl(indicator) ? fullUrl : bareHost;
    }

    private boolean isFullUrl(Indicator indicator) {
        return switch (indicator.type()) {
            case URL -> true;
            case IPV4, DOMAIN -> hasPathOrPort(indicator.value());
            default -> false; // hashes never reach a network MatchPolicy
        };
    }

    /** A path ('/') or a port (':') after the host marks a "full URL" mask. */
    private boolean hasPathOrPort(String value) {
        return value.indexOf('/') >= 0 || value.indexOf(':') >= 0;
    }
}
