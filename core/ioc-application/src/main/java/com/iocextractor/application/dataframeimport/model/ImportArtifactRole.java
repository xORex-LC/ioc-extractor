package com.iocextractor.application.dataframeimport.model;

/** Identifies the primary or a related branch of one logical import row. */
public enum ImportArtifactRole implements ImportPolicyToken {
    /** Primary artifact that owns recognition, compound-row identity and requested slot. */
    PRIMARY("primary"),
    /** Deterministic related-artifact branch accepted or rejected with its primary row. */
    RELATED("related");

    private final String token;

    ImportArtifactRole(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external role token.
     *
     * @param value external value
     * @return parsed role
     */
    public static ImportArtifactRole parse(String value) {
        return ImportPolicyToken.parse(ImportArtifactRole.class, value, "import artifact role");
    }
}
