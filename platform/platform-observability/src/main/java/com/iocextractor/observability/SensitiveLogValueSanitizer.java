package com.iocextractor.observability;

/**
 * Removes credential-bearing URL components before a value reaches a log
 * renderer.
 *
 * <p>The sanitizer deliberately uses a small linear scan instead of
 * {@link java.net.URI}: extracted IOC values may be partial or non-canonical,
 * and observability must neither reject nor normalize them.</p>
 */
public final class SensitiveLogValueSanitizer {

    private static final String REDACTED = "<redacted>";

    private SensitiveLogValueSanitizer() {
    }

    /**
     * Redacts URL user-info and query content while preserving the remaining
     * value, including a fragment when present.
     *
     * @param value raw value, possibly {@code null}
     * @return sanitized value, or {@code null}
     */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return redactQuery(redactUserInfo(value));
    }

    private static String redactUserInfo(String value) {
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return value;
        }
        int authorityStart = scheme + 3;
        int authorityEnd = authorityEnd(value, authorityStart);
        int userInfoEnd = value.lastIndexOf('@', authorityEnd - 1);
        if (userInfoEnd < authorityStart) {
            return value;
        }
        return value.substring(0, authorityStart)
                + REDACTED + '@'
                + value.substring(userInfoEnd + 1);
    }

    private static int authorityEnd(String value, int authorityStart) {
        int end = value.length();
        end = earlier(value.indexOf('/', authorityStart), end);
        end = earlier(value.indexOf('?', authorityStart), end);
        end = earlier(value.indexOf('#', authorityStart), end);
        return end;
    }

    private static int earlier(int candidate, int current) {
        return candidate < 0 ? current : Math.min(candidate, current);
    }

    private static String redactQuery(String value) {
        int query = value.indexOf('?');
        if (query < 0) {
            return value;
        }
        int fragment = value.indexOf('#', query + 1);
        return fragment < 0
                ? value.substring(0, query + 1) + REDACTED
                : value.substring(0, query + 1) + REDACTED + value.substring(fragment);
    }
}
