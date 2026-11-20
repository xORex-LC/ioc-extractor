package com.iocextractor.platform.logging.redaction;

/**
 * Explicit transformations for selected values before they enter a log event.
 * Callers choose what to mask. These methods do not scrub messages, causes,
 * other fields or ambient MDC, and have no severity-dependent bypass.
 */
public final class LogRedaction {
    private static final String URL_MASK = "<redacted>";

    private LogRedaction() { }

    /**
     * Returns a constant without accepting, stringifying or retaining a secret.
     * Use a STRING log field for this representation; numeric/boolean fields still
     * reject text. The caller decides whether an absent source value is omitted.
     *
     * @return exactly {@code [redacted]}
     */
    public static String fullMask() {
        return "[redacted]";
    }

    /**
     * Hides user-info in a {@code ://} authority and content after the first
     * question mark up to the following fragment marker or end of input.
     *
     * <p>This is a lexical transformation for partial input, not URI parsing.
     * Scheme-less user-info, paths and fragments can remain visible. A question
     * mark inside a fragment is still processed. For example,
     * {@code https://u:p@example.test/p?q=secret#f} becomes
     * {@code https://<redacted>@example.test/p?<redacted>#f}.
     *
     * @param value selected input, possibly null
     * @return transformed input using {@code <redacted>}, or null for null input
     */
    public static String urlComponents(String value) {
        if (value == null) {
            return null;
        }
        String withoutCredentials = maskAuthority(value);
        int queryStart = withoutCredentials.indexOf('?');
        if (queryStart < 0) {
            return withoutCredentials;
        }
        int suffixStart = withoutCredentials.indexOf('#', queryStart + 1);
        String suffix = suffixStart < 0 ? "" : withoutCredentials.substring(suffixStart);
        return withoutCredentials.substring(0, queryStart + 1) + URL_MASK + suffix;
    }

    private static String maskAuthority(String input) {
        int separator = input.indexOf("://");
        if (separator < 0) {
            return input;
        }
        int start = separator + 3;
        int end = start;
        while (end < input.length() && "/?#".indexOf(input.charAt(end)) < 0) {
            end++;
        }
        int at = input.lastIndexOf('@', end - 1);
        return at < start ? input
                : input.substring(0, start) + URL_MASK + input.substring(at);
    }
}
