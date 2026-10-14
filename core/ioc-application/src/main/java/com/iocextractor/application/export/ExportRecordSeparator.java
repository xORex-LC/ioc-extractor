package com.iocextractor.application.export;

/** Explicit CSV record endings, independent of the host operating system. */
public enum ExportRecordSeparator {
    /** Unix line feed. */
    LF("\n"),
    /** Carriage return followed by line feed; the original export layout. */
    CRLF("\r\n");

    private final String characters;

    ExportRecordSeparator(String characters) {
        this.characters = characters;
    }

    /** Returns the exact characters written after each CSV record. */
    public String characters() {
        return characters;
    }
}
