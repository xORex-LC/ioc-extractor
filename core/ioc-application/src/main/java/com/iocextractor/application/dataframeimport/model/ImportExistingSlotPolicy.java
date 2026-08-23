package com.iocextractor.application.dataframeimport.model;

/** Resolves a requested export slot that differs from a matched survivor assignment. */
public enum ImportExistingSlotPolicy implements ImportPolicyToken {
    /** Keep the survivor slot and report the mismatch. */
    PRESERVE_EXISTING("preserve-existing"),
    /** Reject the logical row and all of its fan-out branches. */
    REJECT_MISMATCH("reject-mismatch");

    private final String token;

    ImportExistingSlotPolicy(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external existing-slot token.
     *
     * @param value external value
     * @return parsed policy
     */
    public static ImportExistingSlotPolicy parse(String value) {
        return ImportPolicyToken.parse(ImportExistingSlotPolicy.class, value, "existing slot policy");
    }
}
