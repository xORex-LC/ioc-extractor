package com.iocextractor.domain.feature;

/**
 * Structural features of an indicator, derived once and consumed by mask
 * classification (instead of re-parsing the raw string downstream).
 *
 * @param value     normalized indicator value
 * @param host      host without scheme/port/path/query
 * @param hasPort   authority carried a {@code :port}
 * @param hasPath   a path ({@code /…}) follows the authority
 * @param hasQuery  a query ({@code ?…}) is present
 * @param hostKind  classified host kind (IP / registrable / subdomain / onion)
 */
public record IndicatorFeatures(
        String value,
        String host,
        boolean hasPort,
        boolean hasPath,
        boolean hasQuery,
        HostKind hostKind) {

    public boolean isIp() {
        return hostKind == HostKind.IP;
    }
}
