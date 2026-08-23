package com.iocextractor.application.dataframeimport.model;

/** Controls whether mapped artifact values are accepted directly or derived by processing services. */
public enum ImportProcessingMode implements ImportPolicyToken {
    /** Validate explicitly mapped final artifact values without implicit transforms. */
    AS_IS("as-is"),
    /** Run declared raw values through the existing framework-free processing services. */
    PROCESSED("processed");

    private final String token;

    ImportProcessingMode(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external mode token.
     *
     * @param value external value
     * @return parsed mode
     */
    public static ImportProcessingMode parse(String value) {
        return ImportPolicyToken.parse(ImportProcessingMode.class, value, "import processing mode");
    }
}
