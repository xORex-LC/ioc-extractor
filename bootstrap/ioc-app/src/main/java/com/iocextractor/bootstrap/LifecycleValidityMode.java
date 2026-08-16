package com.iocextractor.bootstrap;

/** Operator-facing canonical record-validity mode. */
public enum LifecycleValidityMode implements ConfigSelector {
    DISABLED("disabled"),
    FIXED("fixed");

    private final String token;

    LifecycleValidityMode(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static LifecycleValidityMode parse(String value) {
        return ConfigSelectors.parse(LifecycleValidityMode.class, value, "ioc.lifecycle.validity.mode");
    }
}
