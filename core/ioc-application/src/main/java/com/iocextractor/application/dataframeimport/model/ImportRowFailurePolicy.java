package com.iocextractor.application.dataframeimport.model;

/** Selects whether an invalid logical row rejects itself or the whole delivery. */
public enum ImportRowFailurePolicy implements ImportPolicyToken {
    /** Commit the valid accepted set and report rejected logical rows. */
    ACCEPT_VALID("accept-valid"),
    /** Reject the delivery before canonical mutation when any logical row is invalid. */
    REJECT_DELIVERY("reject-delivery");

    private final String token;

    ImportRowFailurePolicy(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external failure-policy token.
     *
     * @param value external value
     * @return parsed policy
     */
    public static ImportRowFailurePolicy parse(String value) {
        return ImportPolicyToken.parse(ImportRowFailurePolicy.class, value, "import row failure policy");
    }
}
