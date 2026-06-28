package com.iocextractor.adapter.out.transport.smb;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable SMB endpoint configuration used by {@link SmbFileTransport}.
 *
 * <p>The password is defensively copied and never rendered by {@link #toString()}.
 */
public final class SmbEndpointSettings {

    private final String name;
    private final String host;
    private final String share;
    private final String domain;
    private final String username;
    private final char[] password;
    private final boolean encrypt;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration idleTimeout;

    public SmbEndpointSettings(String name,
                               String host,
                               String share,
                               String domain,
                               String username,
                               char[] password,
                               boolean encrypt,
                               Duration connectTimeout,
                               Duration readTimeout,
                               Duration idleTimeout) {
        this.name = requireText(name, "name");
        this.host = requireText(host, "host");
        this.share = requireText(share, "share");
        this.domain = domain == null ? "" : domain;
        this.username = requireText(username, "username");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        this.password = Arrays.copyOf(password, password.length);
        this.encrypt = encrypt;
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.readTimeout = requirePositive(readTimeout, "readTimeout");
        this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
    }

    /** Returns the stable endpoint name used by sync config targets. */
    public String name() {
        return name;
    }

    /** Returns the SMB server hostname or address. */
    public String host() {
        return host;
    }

    /** Returns the SMB share name. */
    public String share() {
        return share;
    }

    /** Returns the authentication domain, or an empty string when not configured. */
    public String domain() {
        return domain;
    }

    /** Returns the SMB username. */
    public String username() {
        return username;
    }

    /** Returns a defensive copy of the endpoint password. */
    public char[] password() {
        return Arrays.copyOf(password, password.length);
    }

    /** Returns whether SMB encryption should be requested. */
    public boolean encrypt() {
        return encrypt;
    }

    /** Returns the socket/connect timeout. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** Returns the SMB read/write timeout. */
    public Duration readTimeout() {
        return readTimeout;
    }

    /** Returns the idle timeout after which cached sessions may be closed. */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    @Override
    public String toString() {
        return "SmbEndpointSettings["
                + "name='" + name + '\''
                + ", host='" + host + '\''
                + ", share='" + share + '\''
                + ", domain='" + domain + '\''
                + ", username='" + username + '\''
                + ", password=<redacted>"
                + ", encrypt=" + encrypt
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", idleTimeout=" + idleTimeout
                + ']';
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
