package com.iocextractor.domain.model;

/**
 * Supported indicator-of-compromise types.
 *
 * <p>The {@code stixType} is the aligned STIX 2.1 Cyber-observable vocabulary,
 * kept as metadata so a future STIX/OpenIOC export sink can reuse it without
 * us adopting STIX as the internal model.
 */
public enum IndicatorType {

    IPV4(IndicatorCategory.NETWORK, "ipv4-addr"),
    DOMAIN(IndicatorCategory.NETWORK, "domain-name"),
    URL(IndicatorCategory.NETWORK, "url"),

    MD5(IndicatorCategory.FILE, "file:hashes.MD5"),
    SHA1(IndicatorCategory.FILE, "file:hashes.'SHA-1'"),
    SHA256(IndicatorCategory.FILE, "file:hashes.'SHA-256'");

    private final IndicatorCategory category;
    private final String stixType;

    IndicatorType(IndicatorCategory category, String stixType) {
        this.category = category;
        this.stixType = stixType;
    }

    public IndicatorCategory category() {
        return category;
    }

    public String stixType() {
        return stixType;
    }
}
