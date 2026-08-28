package com.iocextractor.bootstrap;

/** Operator-facing SMB session-encryption policy selector. */
public enum SmbEncryptionMode implements ConfigSelector {
    DISABLED("disabled"),
    PREFERRED("preferred"),
    REQUIRED("required");

    private final String token;

    SmbEncryptionMode(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /** Parses the closed SMB encryption vocabulary used by endpoint configuration. */
    public static SmbEncryptionMode parse(String value) {
        return ConfigSelectors.parse(
                SmbEncryptionMode.class, value, "ioc.sync.endpoints[].smb.encryption");
    }
}
