package com.iocextractor.bootstrap;

/** Application runtime mode selector. */
public enum RuntimeMode implements ConfigSelector {
    ONESHOT("oneshot"),
    DAEMON("daemon");

    public static final String ONESHOT_VALUE = "oneshot";
    public static final String DAEMON_VALUE = "daemon";

    private final String token;

    RuntimeMode(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public boolean isDaemon() {
        return this == DAEMON;
    }

    public static RuntimeMode parse(String value) {
        return ConfigSelectors.parse(RuntimeMode.class, value, "ioc.runtime.mode");
    }
}
