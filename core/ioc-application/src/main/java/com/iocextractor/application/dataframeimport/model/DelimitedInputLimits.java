package com.iocextractor.application.dataframeimport.model;

/** Strict parser resource boundaries applied before rows reach staging. */
public record DelimitedInputLimits(
        long maximumRows,
        int maximumColumns,
        int maximumFieldCharacters,
        int maximumRecordCharacters) {

    /** Enforces positive, internally consistent limits. */
    public DelimitedInputLimits {
        if (maximumRows < 1 || maximumColumns < 1
                || maximumFieldCharacters < 1 || maximumRecordCharacters < maximumFieldCharacters) {
            throw new IllegalArgumentException("Delimited input limits must be positive and internally consistent");
        }
    }

    /** Conservative application default; operator configuration may tighten it later. */
    public static DelimitedInputLimits defaults() {
        return new DelimitedInputLimits(1_000_000, 512, 1_048_576, 8_388_608);
    }
}
