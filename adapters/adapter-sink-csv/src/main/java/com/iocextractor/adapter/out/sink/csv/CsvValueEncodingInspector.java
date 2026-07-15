package com.iocextractor.adapter.out.sink.csv;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.List;
import java.util.Objects;

/** Counts logical CSV values that require charset replacement. */
final class CsvValueEncodingInspector {

    private final CharsetEncoder encoder;
    private int affectedValues;
    private int affectedRows;
    private int affectedHeaderValues;

    CsvValueEncodingInspector(Charset charset) {
        this.encoder = Objects.requireNonNull(charset, "charset").newEncoder();
    }

    /** Inspects each header value as one logical occurrence. */
    void inspectHeader(List<String> header) {
        Objects.requireNonNull(header, "header").forEach(value -> {
            if (requiresReplacement(value)) {
                affectedHeaderValues++;
            }
        });
    }

    /** Inspects each data cell and counts the row once when any cell is lossy. */
    void inspectRow(List<String> values, String nullValue) {
        Objects.requireNonNull(values, "values");
        boolean affected = false;
        for (String value : values) {
            String rendered = value == null ? nullValue : value;
            if (requiresReplacement(rendered)) {
                affectedValues++;
                affected = true;
            }
        }
        if (affected) {
            affectedRows++;
        }
    }

    /** Returns the immutable count snapshot. */
    CsvEncodingLoss loss() {
        return new CsvEncodingLoss(affectedValues, affectedRows, affectedHeaderValues);
    }

    private boolean requiresReplacement(String value) {
        return value != null && !value.isEmpty() && !encoder.canEncode(value);
    }

    /** Exact counts of lossy logical values in one successfully written projection. */
    record CsvEncodingLoss(int affectedValues, int affectedRows, int affectedHeaderValues) {

        boolean detected() {
            return affectedValues > 0 || affectedHeaderValues > 0;
        }
    }
}
