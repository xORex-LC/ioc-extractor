package com.iocextractor.application.dataframeimport.model;

/** Defines how one incoming tri-state cell may affect an existing public field. */
public enum ImportMergePolicy implements ImportPolicyToken {
    /** Preserve every field of an existing record. */
    KEEP_EXISTING("keep-existing", 0),
    /** Populate a non-null incoming value only when the existing field is null. */
    FILL_MISSING("fill-missing", 1),
    /** Replace an existing field only with an incoming non-null value. */
    REPLACE_NON_NULL("replace-non-null", 2),
    /** Treat incoming null/value as authoritative clear/replace instructions. */
    AUTHORITATIVE("authoritative", 3),
    /** Reject a null/non-equal value that conflicts with existing data. */
    REJECT_CONFLICT("reject-conflict", 0);

    private final String token;
    private final int authorityLevel;

    ImportMergePolicy(String token, int authorityLevel) {
        this.token = token;
        this.authorityLevel = authorityLevel;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Returns the destructive authority level used by source ceilings.
     * Conflict rejection is non-destructive and therefore level zero.
     *
     * @return level from zero through three
     */
    public int authorityLevel() {
        return authorityLevel;
    }

    /**
     * Tests whether this policy fits below a configured source ceiling.
     *
     * @param ceiling source authority ceiling
     * @return {@code true} when this policy is permitted
     */
    public boolean isAllowedBy(ImportMergePolicy ceiling) {
        return ceiling != null && authorityLevel <= ceiling.authorityLevel;
    }

    /**
     * Parses an external merge token.
     *
     * @param value external value
     * @return parsed policy
     */
    public static ImportMergePolicy parse(String value) {
        return ImportPolicyToken.parse(ImportMergePolicy.class, value, "import merge policy");
    }
}
