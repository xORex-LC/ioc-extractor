package com.iocextractor.application.export;

import java.util.Objects;

/**
 * Storage-neutral description of the bytes emitted for one export profile.
 */
public record ExportFormat(String type,
                           String charset,
                           String delimiter,
                           String quote,
                           String nullLiteral,
                           ExportRecordSeparator recordSeparator,
                           ExportQuoting headerQuoting,
                           ExportQuoting valueQuoting) {

    /** Original wire version, with CRLF and quoted non-null cells. */
    public static final int LEGACY_MANIFEST_VERSION = 1;
    /** Wire version that records explicit CSV layout policies. */
    public static final int CONFIGURABLE_CSV_MANIFEST_VERSION = 2;

    public ExportFormat {
        type = requireText(type, "type");
        charset = requireText(charset, "charset");
        delimiter = Objects.requireNonNull(delimiter, "delimiter");
        quote = Objects.requireNonNull(quote, "quote");
        nullLiteral = Objects.requireNonNull(nullLiteral, "nullLiteral");
        recordSeparator = Objects.requireNonNull(recordSeparator, "recordSeparator");
        headerQuoting = Objects.requireNonNull(headerQuoting, "headerQuoting");
        valueQuoting = Objects.requireNonNull(valueQuoting, "valueQuoting");
    }

    /** Preserves the original layout for existing callers and stored v1 manifests. */
    public ExportFormat(String type, String charset, String delimiter, String quote, String nullLiteral) {
        this(type, charset, delimiter, quote, nullLiteral,
                ExportRecordSeparator.CRLF, ExportQuoting.ALL, ExportQuoting.ALL);
    }

    /** Returns the oldest manifest version that describes this layout without ambiguity. */
    public int requiredManifestVersion() {
        return recordSeparator == ExportRecordSeparator.CRLF
                && headerQuoting == ExportQuoting.ALL && valueQuoting == ExportQuoting.ALL
                ? LEGACY_MANIFEST_VERSION : CONFIGURABLE_CSV_MANIFEST_VERSION;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Export format " + field + " must not be blank");
        }
        return value;
    }
}
