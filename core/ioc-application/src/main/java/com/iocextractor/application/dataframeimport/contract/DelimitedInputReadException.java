package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.common.IocExtractorException;

import java.util.Arrays;
import java.util.Optional;

/** Strict parser-boundary failure safe to classify during exact-one recognition. */
public class DelimitedInputReadException extends IocExtractorException {

    private final Reason reason;

    /** Creates a safe structural or resource-limit failure. */
    public DelimitedInputReadException(Reason reason, String message) {
        super(message);
        this.reason = reason == null ? Reason.PARSER_FAILURE : reason;
    }

    /** Creates a safe decoder or I/O failure with its cause retained. */
    public DelimitedInputReadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? Reason.PARSER_FAILURE : reason;
    }

    /** Returns the value-free parser-boundary failure classification. */
    public Reason reason() {
        return reason;
    }

    /** Stable value-free parser failure reasons suitable for reports and logs. */
    public enum Reason {
        UNSUPPORTED_CHARSET("unsupported_charset"),
        MALFORMED_ENCODING("malformed_encoding"),
        RECORD_SEPARATOR_MISMATCH("record_separator_mismatch"),
        RESOURCE_LIMIT_EXCEEDED("resource_limit_exceeded"),
        HEADER_MISMATCH("header_mismatch"),
        COLUMN_COUNT_MISMATCH("column_count_mismatch"),
        PARSER_FAILURE("parser_failure");

        private static final String REPORT_PREFIX = "IMPORT.INPUT_INVALID.";

        private final String value;

        Reason(String value) {
            this.value = value;
        }

        /** Returns the stable ECS scalar value. */
        public String value() {
            return value;
        }

        /** Returns the stable protected-report code for this reason. */
        public String reportCode() {
            return REPORT_PREFIX + name();
        }

        /** Resolves a protected-report reason code without accepting arbitrary input. */
        public static Optional<Reason> fromReportCode(String code) {
            return Arrays.stream(values()).filter(reason -> reason.reportCode().equals(code)).findFirst();
        }
    }
}
