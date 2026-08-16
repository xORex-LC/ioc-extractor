package com.iocextractor.bootstrap;

/** Explicit activation decision for canonical rows created before TTL support. */
public enum ExistingRecordsPolicy implements ConfigSelector {
    REJECT("reject"),
    EXPIRE("expire");

    private final String token;

    ExistingRecordsPolicy(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static ExistingRecordsPolicy parse(String value) {
        return ConfigSelectors.parse(
                ExistingRecordsPolicy.class, value, "ioc.lifecycle.validity.existing-records");
    }
}
