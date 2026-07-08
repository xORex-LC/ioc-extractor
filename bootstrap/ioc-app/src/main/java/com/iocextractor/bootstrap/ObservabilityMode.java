package com.iocextractor.bootstrap;

/** Logging/observability profile selector. */
public enum ObservabilityMode implements ConfigSelector {
    ONESHOT("oneshot"),
    DAEMON("daemon");

    private final String token;

    ObservabilityMode(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static ObservabilityMode parse(String value) {
        return ConfigSelectors.parse(ObservabilityMode.class, value, "ioc.observability.mode");
    }
}
