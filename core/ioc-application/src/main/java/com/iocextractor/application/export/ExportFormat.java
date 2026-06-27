package com.iocextractor.application.export;

import java.util.Objects;

/**
 * Storage-neutral description of the bytes emitted for one export profile.
 */
public record ExportFormat(String type,
                           String charset,
                           String delimiter,
                           String quote,
                           String nullLiteral) {

    public ExportFormat {
        type = requireText(type, "type");
        charset = requireText(charset, "charset");
        delimiter = Objects.requireNonNull(delimiter, "delimiter");
        quote = Objects.requireNonNull(quote, "quote");
        nullLiteral = Objects.requireNonNull(nullLiteral, "nullLiteral");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Export format " + field + " must not be blank");
        }
        return value;
    }
}
