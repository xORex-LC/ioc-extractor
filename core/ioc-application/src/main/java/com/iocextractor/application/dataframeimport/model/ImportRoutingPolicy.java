package com.iocextractor.application.dataframeimport.model;

/** Controls deterministic artifact fan-out for one logical imported row. */
public enum ImportRoutingPolicy implements ImportPolicyToken {
    /** Emit only the declared primary artifact branch. */
    TARGET_ONLY("target-only"),
    /** Emit the primary branch and explicitly declared compatible related branches. */
    RELATED_ARTIFACTS("related-artifacts");

    private final String token;

    ImportRoutingPolicy(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external routing token.
     *
     * @param value external value
     * @return parsed policy
     */
    public static ImportRoutingPolicy parse(String value) {
        return ImportPolicyToken.parse(ImportRoutingPolicy.class, value, "import routing policy");
    }
}
