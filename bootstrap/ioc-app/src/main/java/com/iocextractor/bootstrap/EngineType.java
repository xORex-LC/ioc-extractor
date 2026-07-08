package com.iocextractor.bootstrap;

/** Regex engine selector. */
public enum EngineType implements ConfigSelector {
    RE2J("re2j"),
    JDK("jdk");

    public static final String RE2J_VALUE = "re2j";
    public static final String JDK_VALUE = "jdk";

    private final String token;

    EngineType(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static EngineType parse(String value) {
        return ConfigSelectors.parse(EngineType.class, value, "ioc.engine");
    }
}
