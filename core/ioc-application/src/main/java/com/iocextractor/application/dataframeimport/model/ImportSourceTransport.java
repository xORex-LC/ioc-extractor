package com.iocextractor.application.dataframeimport.model;

/** Declared transport family for a managed dataframe-import source. */
public enum ImportSourceTransport implements ImportPolicyToken {
    /** Dedicated local filesystem source. */
    LOCAL("local"),
    /** Dedicated SMB source referencing a configured endpoint. */
    SMB("smb");

    private final String token;

    ImportSourceTransport(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external transport token.
     *
     * @param value external value
     * @return parsed transport
     */
    public static ImportSourceTransport parse(String value) {
        return ImportPolicyToken.parse(ImportSourceTransport.class, value, "import source transport");
    }
}
