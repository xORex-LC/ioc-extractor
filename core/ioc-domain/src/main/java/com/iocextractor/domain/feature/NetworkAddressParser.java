package com.iocextractor.domain.feature;

import java.util.Locale;
import java.util.Objects;

/** Parses supported network addresses without URI dereferencing or DNS lookup. */
public final class NetworkAddressParser {

    /** Stable input-dependent reason, suitable for an expected view failure. */
    public enum FailureReason {
        EMPTY, UNSUPPORTED_SCHEME, INVALID_AUTHORITY, INVALID_HOST, INVALID_PORT,
        UNSUPPORTED_ADDRESS_FORM
    }

    /** Structural information shared by host derivation and feature classification. */
    public record Address(String host, boolean hasPort, boolean hasPath,
                          boolean hasQuery, boolean hasFragment, boolean isIpv4) { }

    /** One successfully parsed address or one expected input failure. */
    public record Result(Address address, FailureReason failure) {
        public Result {
            if ((address == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one parse outcome is required");
            }
        }

        public static Result available(Address address) {
            return new Result(Objects.requireNonNull(address), null);
        }

        public static Result unavailable(FailureReason reason) {
            return new Result(null, Objects.requireNonNull(reason));
        }

        public boolean isAvailable() {
            return address != null;
        }
    }

    /** Parses an exact, already refanged/normalized IOC value. */
    public Result parse(String value) {
        if (value == null || value.isEmpty()) {
            return Result.unavailable(FailureReason.EMPTY);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index)) || Character.isISOControl(value.charAt(index))) {
                return Result.unavailable(FailureReason.UNSUPPORTED_ADDRESS_FORM);
            }
        }
        String rest = stripSupportedScheme(value);
        if (rest == null) {
            return Result.unavailable(FailureReason.UNSUPPORTED_SCHEME);
        }
        int authorityEnd = authorityEnd(rest);
        Result authority = parseAuthority(rest.substring(0, authorityEnd));
        if (!authority.isAvailable()) {
            return authority;
        }
        return parseSuffix(authority.address(), rest.substring(authorityEnd));
    }

    private static String stripSupportedScheme(String value) {
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) {
            return value;
        }
        String scheme = value.substring(0, schemeEnd);
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                ? value.substring(schemeEnd + 3) : null;
    }

    private static int authorityEnd(String rest) {
        int authorityEnd = rest.length();
        for (char separator : new char[] {'/', '?', '#'}) {
            int position = rest.indexOf(separator);
            if (position >= 0) {
                authorityEnd = Math.min(authorityEnd, position);
            }
        }
        return authorityEnd;
    }

    private static Result parseAuthority(String authority) {
        if (authority.isEmpty() || authority.indexOf('@') >= 0 || authority.indexOf('[') >= 0
                || authority.indexOf(']') >= 0 || authority.indexOf('\\') >= 0) {
            return Result.unavailable(FailureReason.INVALID_AUTHORITY);
        }
        String host = authority;
        boolean hasPort = false;
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            if (authority.indexOf(':', colon + 1) >= 0) {
                return Result.unavailable(FailureReason.UNSUPPORTED_ADDRESS_FORM);
            }
            host = authority.substring(0, colon);
            String port = authority.substring(colon + 1);
            if (!validPort(port)) {
                return Result.unavailable(FailureReason.INVALID_PORT);
            }
            hasPort = true;
        }
        boolean ipv4 = validIpv4(host);
        if (!ipv4 && !validDomain(host)) {
            return Result.unavailable(FailureReason.INVALID_HOST);
        }
        return Result.available(new Address(host.toLowerCase(Locale.ROOT), hasPort,
                false, false, false, ipv4));
    }

    private static Result parseSuffix(Address authority, String suffix) {
        int query = suffix.indexOf('?');
        int fragment = suffix.indexOf('#');
        if (fragment >= 0 && suffix.indexOf('#', fragment + 1) >= 0) {
            return Result.unavailable(FailureReason.UNSUPPORTED_ADDRESS_FORM);
        }
        boolean hasPath = suffix.startsWith("/");
        return Result.available(new Address(authority.host(), authority.hasPort(), hasPath,
                query >= 0 && (fragment < 0 || query < fragment), fragment >= 0, authority.isIpv4()));
    }

    private static boolean validPort(String value) {
        if (value.isEmpty() || value.length() > 5) {
            return false;
        }
        int port = 0;
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
            port = port * 10 + digit - '0';
        }
        return port > 0 && port <= 65535;
    }

    private static boolean validIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            int number = 0;
            for (int index = 0; index < octet.length(); index++) {
                char digit = octet.charAt(index);
                if (digit < '0' || digit > '9') {
                    return false;
                }
                number = number * 10 + digit - '0';
            }
            if (number > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean validDomain(String value) {
        if (value.length() > 253) {
            return false;
        }
        String[] labels = value.split("\\.", -1);
        if (labels.length < 2 || !alphabeticTld(labels[labels.length - 1])) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || !asciiAlphanumeric(label.charAt(0))
                    || !asciiAlphanumeric(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int index = 1; index < label.length() - 1; index++) {
                char character = label.charAt(index);
                if (!asciiAlphanumeric(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean alphabeticTld(String label) {
        if (label.length() < 2 || label.length() > 63) {
            return false;
        }
        for (int index = 0; index < label.length(); index++) {
            char character = label.charAt(index);
            if ((character < 'a' || character > 'z') && (character < 'A' || character > 'Z')) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiAlphanumeric(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9');
    }
}
