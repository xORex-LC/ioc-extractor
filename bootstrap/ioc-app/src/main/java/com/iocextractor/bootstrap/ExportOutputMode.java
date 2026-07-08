package com.iocextractor.bootstrap;

/** Export profile output mode selector. */
public enum ExportOutputMode implements ConfigSelector {
    COMPLETE("complete"),
    APPEND("append");

    public static final String COMPLETE_VALUE = "complete";
    public static final String APPEND_VALUE = "append";

    private final String token;

    ExportOutputMode(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static ExportOutputMode parse(String value) {
        return ConfigSelectors.parse(ExportOutputMode.class, value, "ioc.export.profiles[].output-mode");
    }
}
