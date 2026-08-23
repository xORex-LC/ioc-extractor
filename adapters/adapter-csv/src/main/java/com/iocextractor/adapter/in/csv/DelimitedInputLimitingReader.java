package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

/** Enforces decoded field and logical-record limits before CSV tokenization allocates them. */
final class DelimitedInputLimitingReader extends FilterReader {

    private final char delimiter;
    private final char quote;
    private final int maximumFieldCharacters;
    private final int maximumRecordCharacters;
    private int fieldCharacters;
    private int recordCharacters;
    private boolean atFieldStart = true;
    private boolean inQuotes;
    private boolean quotePending;
    private boolean recordCarriageReturn;

    DelimitedInputLimitingReader(Reader delegate,
                                 DelimitedDialect dialect,
                                 DelimitedInputLimits limits) {
        super(Objects.requireNonNull(delegate, "delegate"));
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(limits, "limits");
        delimiter = dialect.delimiter();
        quote = dialect.quote();
        maximumFieldCharacters = limits.maximumFieldCharacters();
        maximumRecordCharacters = limits.maximumRecordCharacters();
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            accept((char) value);
        }
        return value;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        for (int index = offset; index < offset + Math.max(count, 0); index++) {
            accept(buffer[index]);
        }
        return count;
    }

    private void accept(char character) throws IOException {
        if (recordCarriageReturn) {
            recordCarriageReturn = false;
            finishRecord();
            return;
        }
        if (inQuotes) {
            acceptQuoted(character);
            return;
        }
        acceptUnquoted(character);
    }

    private void acceptQuoted(char character) throws IOException {
        if (quotePending) {
            quotePending = false;
            if (character == quote) {
                incrementRecord();
                incrementField();
                return;
            }
            inQuotes = false;
            acceptUnquoted(character);
            return;
        }
        incrementRecord();
        if (character == quote) {
            quotePending = true;
        } else {
            incrementField();
        }
    }

    private void acceptUnquoted(char character) throws IOException {
        if (character == '\r') {
            recordCarriageReturn = true;
            return;
        }
        if (character == '\n') {
            finishRecord();
            return;
        }
        incrementRecord();
        if (character == delimiter) {
            finishField();
        } else if (character == quote && atFieldStart) {
            inQuotes = true;
            atFieldStart = false;
        } else {
            incrementField();
            atFieldStart = false;
        }
    }

    private void incrementRecord() throws IOException {
        recordCharacters = Math.incrementExact(recordCharacters);
        if (recordCharacters > maximumRecordCharacters) {
            throw limit("record");
        }
    }

    private void incrementField() throws IOException {
        fieldCharacters = Math.incrementExact(fieldCharacters);
        if (fieldCharacters > maximumFieldCharacters) {
            throw limit("field");
        }
    }

    private void finishField() {
        fieldCharacters = 0;
        atFieldStart = true;
    }

    private void finishRecord() {
        recordCharacters = 0;
        finishField();
    }

    private IOException limit(String unit) {
        return new InputLimitException("Delimited input exceeds the configured " + unit + " limit");
    }

    /** Distinguishes safe resource-limit diagnostics from arbitrary parser I/O failures. */
    static final class InputLimitException extends IOException {

        private static final long serialVersionUID = 1L;

        private InputLimitException(String message) {
            super(message);
        }
    }
}
