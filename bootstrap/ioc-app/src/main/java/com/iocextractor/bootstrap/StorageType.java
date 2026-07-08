package com.iocextractor.bootstrap;

/** Storage backend selector. */
public enum StorageType implements ConfigSelector {
    JDBC("jdbc");

    public static final String JDBC_VALUE = "jdbc";

    private final String token;

    StorageType(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static StorageType parse(String value) {
        return ConfigSelectors.parse(StorageType.class, value, "ioc.storage.*.type");
    }
}
