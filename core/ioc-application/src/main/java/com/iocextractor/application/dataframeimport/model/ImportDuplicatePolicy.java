package com.iocextractor.application.dataframeimport.model;

/** Controls deterministic handling of repeated logical keys within one delivery. */
public enum ImportDuplicatePolicy implements ImportPolicyToken {
    /** Combine compatible cells without using source order as a winner. */
    COALESCE("coalesce"),
    /** Retain the smallest physical source row and ignore later duplicates. */
    KEEP_FIRST("keep-first");

    private final String token;

    ImportDuplicatePolicy(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external duplicate token.
     *
     * @param value external value
     * @return parsed policy
     */
    public static ImportDuplicatePolicy parse(String value) {
        return ImportPolicyToken.parse(ImportDuplicatePolicy.class, value, "import duplicate policy");
    }
}
