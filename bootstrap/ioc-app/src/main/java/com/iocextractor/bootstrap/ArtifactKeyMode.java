package com.iocextractor.bootstrap;

/** Artifact identity key derivation mode. */
public enum ArtifactKeyMode implements ConfigSelector {
    COMPOSITE("composite"),
    FIRST_NON_EMPTY("first-non-empty");

    public static final String COMPOSITE_VALUE = "composite";
    public static final String FIRST_NON_EMPTY_VALUE = "first-non-empty";

    private final String token;

    ArtifactKeyMode(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static ArtifactKeyMode parse(String value) {
        return ConfigSelectors.parse(ArtifactKeyMode.class, value, "ioc.artifact-identity.artifacts[].key-mode");
    }
}
