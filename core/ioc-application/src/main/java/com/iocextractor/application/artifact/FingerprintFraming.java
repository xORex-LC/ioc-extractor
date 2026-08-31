package com.iocextractor.application.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Authoritative length-prefixed UTF-8 framing for durable application fingerprints. */
public final class FingerprintFraming {

    private FingerprintFraming() {
    }

    /** Appends one required value without concatenation ambiguity. */
    public static void add(MessageDigest digest, String value) {
        Objects.requireNonNull(digest, "digest");
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }

    /** Appends a nullable value while preserving a distinction between {@code null} and empty text. */
    public static void addNullable(MessageDigest digest, String value) {
        Objects.requireNonNull(digest, "digest");
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        add(digest, value);
    }
}
