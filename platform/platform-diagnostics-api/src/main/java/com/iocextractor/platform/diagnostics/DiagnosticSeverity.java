package com.iocextractor.platform.diagnostics;

/**
 * Severity levels used by data-processing diagnostics.
 */
public enum DiagnosticSeverity {
    /** Fine-grained detail, usually disabled in operational output. */
    TRACE,
    /** Information useful while investigating a processing path. */
    DEBUG,
    /** An expected processing fact. */
    INFO,
    /** A condition the caller should inspect; built-in policies continue. */
    WARN,
    /** A failure that fail-fast rejects and collect-and-continue permits. */
    ERROR,
    /** A failure rejected by both built-in policies. */
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
