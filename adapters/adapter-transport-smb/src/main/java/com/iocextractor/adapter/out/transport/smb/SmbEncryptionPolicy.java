package com.iocextractor.adapter.out.transport.smb;

/** SMB session-encryption policy applied while negotiating an endpoint connection. */
public enum SmbEncryptionPolicy {
    /** Do not request client-preferred SMB session encryption. */
    DISABLED(false, false),
    /** Request SMB3 encryption while allowing an unencrypted SMB2/3 fallback. */
    PREFERRED(true, false),
    /** Require negotiated SMB3 encryption and reject every fallback. */
    REQUIRED(true, true);

    private final boolean requestsEncryption;
    private final boolean requiresEncryption;

    SmbEncryptionPolicy(boolean requestsEncryption, boolean requiresEncryption) {
        this.requestsEncryption = requestsEncryption;
        this.requiresEncryption = requiresEncryption;
    }

    /** Returns whether the client advertises and prefers SMB encryption. */
    public boolean requestsEncryption() {
        return requestsEncryption;
    }

    /** Returns whether connection establishment must fail without effective SMB3 encryption. */
    public boolean requiresEncryption() {
        return requiresEncryption;
    }
}
