package com.iocextractor.bootstrap;

/** Public artifact id generation direction. */
public enum ArtifactIdStrategy implements ConfigSelector {
    ASCENDING("ascending"),
    DESCENDING("descending");

    public static final String ASCENDING_VALUE = "ascending";
    public static final String DESCENDING_VALUE = "descending";

    private final String token;

    ArtifactIdStrategy(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static ArtifactIdStrategy parse(String value) {
        return ConfigSelectors.parse(ArtifactIdStrategy.class, value, "ioc.sink.artifacts[].id.strategy");
    }
}
