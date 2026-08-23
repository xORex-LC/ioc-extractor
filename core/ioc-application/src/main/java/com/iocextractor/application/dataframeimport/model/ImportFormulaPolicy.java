package com.iocextractor.application.dataframeimport.model;

/** Controls preservation of spreadsheet-formula-dangerous free text. */
public enum ImportFormulaPolicy implements ImportPolicyToken {
    /** Reject dangerous cells without silently changing their bytes. */
    REJECT("reject"),
    /** Preserve exact text only for an explicitly machine-only trust boundary. */
    MACHINE_ONLY_PRESERVE("machine-only-preserve");

    private final String token;

    ImportFormulaPolicy(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external formula-policy token.
     *
     * @param value external value
     * @return parsed policy
     */
    public static ImportFormulaPolicy parse(String value) {
        return ImportPolicyToken.parse(ImportFormulaPolicy.class, value, "import formula policy");
    }
}
