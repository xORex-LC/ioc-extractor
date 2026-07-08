package com.iocextractor.bootstrap;

/** Remote sync transport selector. */
public enum SyncTransport implements ConfigSelector {
    SMB("smb");

    public static final String SMB_VALUE = "smb";

    private final String token;

    SyncTransport(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static SyncTransport parse(String value) {
        return ConfigSelectors.parse(SyncTransport.class, value, "ioc.sync.endpoints[].transport");
    }
}
