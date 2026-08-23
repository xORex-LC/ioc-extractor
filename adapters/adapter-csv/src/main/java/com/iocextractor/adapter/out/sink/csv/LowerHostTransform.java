package com.iocextractor.adapter.out.sink.csv;

import java.util.Locale;

/**
 * Transform {@code lower-host}: lower-cases the scheme and host only, preserving
 * the path/query/token case (which is case-sensitive). Matches the reference
 * convention — e.g. {@code …/MSI_173518.png} and {@code …:AAEowj…} keep their case.
 */
public final class LowerHostTransform implements Transform {

    @Override
    public String apply(String value, String arg) {
        if (value == null) {
            return null;
        }
        int schemeIdx = value.indexOf("://");
        String scheme = schemeIdx >= 0 ? value.substring(0, schemeIdx + 3) : "";
        String rest = schemeIdx >= 0 ? value.substring(schemeIdx + 3) : value;

        int authorityEnd = rest.length();
        int slash = rest.indexOf('/');
        int query = rest.indexOf('?');
        if (slash >= 0) {
            authorityEnd = Math.min(authorityEnd, slash);
        }
        if (query >= 0) {
            authorityEnd = Math.min(authorityEnd, query);
        }

        String host = rest.substring(0, authorityEnd);
        String tail = rest.substring(authorityEnd);
        return (scheme + host).toLowerCase(Locale.ROOT) + tail;
    }
}
