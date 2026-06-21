package com.iocextractor.adapter.out.psl;

import com.google.common.net.InternetDomainName;
import com.iocextractor.domain.feature.HostClassifier;
import com.iocextractor.domain.feature.HostKind;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@link HostClassifier} backed by Guava's Public Suffix List.
 *
 * <p>Uses the <em>public</em> suffix list (which includes the PRIVATE section),
 * so dynamic-DNS / hosting providers like {@code duckdns.org}, {@code workers.dev}
 * are treated as suffixes — {@code x.duckdns.org} is REGISTRABLE, while a provider
 * not on the list (e.g. {@code tw1.ru}) makes {@code cs371620.tw1.ru} a SUBDOMAIN.
 */
public final class PslHostClassifier implements HostClassifier {

    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    @Override
    public HostKind classify(String host) {
        if (host == null || host.isEmpty()) {
            return HostKind.UNKNOWN;
        }
        if (IPV4.matcher(host).matches()) {
            return HostKind.IP;
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".onion")) {
            return HostKind.ONION;
        }
        try {
            InternetDomainName domain = InternetDomainName.from(name);
            if (domain.isTopPrivateDomain()) {
                return HostKind.REGISTRABLE;
            }
            if (domain.isUnderPublicSuffix()) {
                return HostKind.SUBDOMAIN;
            }
            return HostKind.UNKNOWN;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return HostKind.UNKNOWN;
        }
    }
}
