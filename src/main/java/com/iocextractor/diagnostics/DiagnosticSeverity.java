package com.iocextractor.diagnostics;

/**
 * Severity levels used by data-processing diagnostics.
 */
public enum DiagnosticSeverity {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL;

    /**
     * Returns whether this severity should be treated as an error by failure
     * policies and result helpers.
     *
     * @return {@code true} for {@link #ERROR} and {@link #FATAL}
     */
    public boolean isErrorOrWorse() {
        return this == ERROR || this == FATAL;
    }
}
