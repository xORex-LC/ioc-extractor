package com.iocextractor.application.dataframeimport.model;

/** Declared record-separator policy at the library-neutral CSV boundary. */
public enum ImportRecordSeparator implements ImportPolicyToken {
    /** Accept CRLF and LF records while preserving strict quoted-record parsing. */
    CRLF_OR_LF("crlf-or-lf"),
    /** Require LF record separators. */
    LF("lf"),
    /** Require CRLF record separators. */
    CRLF("crlf");

    private final String token;

    ImportRecordSeparator(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * Parses an external separator token.
     *
     * @param value external value
     * @return parsed separator policy
     */
    public static ImportRecordSeparator parse(String value) {
        return ImportPolicyToken.parse(ImportRecordSeparator.class, value, "import record separator");
    }
}
