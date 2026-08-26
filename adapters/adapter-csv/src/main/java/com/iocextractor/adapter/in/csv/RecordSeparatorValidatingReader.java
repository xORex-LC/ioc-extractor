package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

/** Streaming physical line-ending validator applied before CSV tokenization. */
final class RecordSeparatorValidatingReader extends FilterReader {

    private final ImportRecordSeparator policy;
    private boolean pendingCarriageReturn;

    RecordSeparatorValidatingReader(Reader delegate, ImportRecordSeparator policy) {
        super(Objects.requireNonNull(delegate, "delegate"));
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        validate(value);
        return value;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count < 0) {
            validate(-1);
            return count;
        }
        for (int index = offset; index < offset + count; index++) {
            validate(buffer[index]);
        }
        return count;
    }

    private void validate(int value) throws IOException {
        if (value < 0) {
            if (pendingCarriageReturn) {
                throw invalid();
            }
            return;
        }
        char character = (char) value;
        if (policy == ImportRecordSeparator.LF) {
            if (character == '\r') {
                throw invalid();
            }
            return;
        }
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            if (character != '\n') {
                throw invalid();
            }
            return;
        }
        if (character == '\r') {
            pendingCarriageReturn = true;
        } else if (character == '\n' && policy == ImportRecordSeparator.CRLF) {
            throw invalid();
        }
    }

    private IOException invalid() {
        return new RecordSeparatorException("Input uses a record separator outside the declared dialect");
    }

    /** Distinguishes a declared-dialect mismatch from other parser I/O failures. */
    static final class RecordSeparatorException extends IOException {

        private static final long serialVersionUID = 1L;

        private RecordSeparatorException(String message) {
            super(message);
        }
    }
}
